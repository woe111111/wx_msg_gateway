"""Local WeChat and WeCom test gateway. Phone operations are performed by the APK."""
import argparse
import hmac
import json
import os
import sqlite3
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from http.cookies import CookieError, SimpleCookie
from v1 import ApiError, Gateway, overlap as suffix_prefix
from urllib.parse import parse_qs, urlsplit


PLATFORMS = {"weixin": "微信", "wecom": "企业微信"}
CLAIM_SECONDS = 100
LATE_OBSERVATION_SECONDS = 300

class Store:
    def __init__(self, path):
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.lock = threading.Lock()
        self.db.executescript("""
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS groups (
                id TEXT PRIMARY KEY, platform TEXT NOT NULL, name TEXT NOT NULL, title TEXT NOT NULL,
                routable INTEGER NOT NULL DEFAULT 0, snapshot TEXT NOT NULL DEFAULT '[]',
                UNIQUE(platform,name));
            CREATE TABLE IF NOT EXISTS captures (
                platform TEXT NOT NULL, id TEXT NOT NULL, PRIMARY KEY(platform,id));
            CREATE TABLE IF NOT EXISTS messages (
                seq INTEGER PRIMARY KEY AUTOINCREMENT, group_id TEXT NOT NULL,
                sender TEXT, text TEXT NOT NULL, observed REAL NOT NULL, quality TEXT NOT NULL);
            CREATE INDEX IF NOT EXISTS messages_group_seq ON messages(group_id,seq);
            CREATE TABLE IF NOT EXISTS sends (
                id TEXT PRIMARY KEY, group_id TEXT NOT NULL, text TEXT NOT NULL,
                state TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '', created REAL NOT NULL,
                deadline REAL NOT NULL, request_key TEXT UNIQUE, observed_seq INTEGER);
            CREATE TABLE IF NOT EXISTS event_log (
                seq INTEGER PRIMARY KEY AUTOINCREMENT, event_id TEXT UNIQUE,
                created REAL NOT NULL, source TEXT NOT NULL, detail TEXT NOT NULL);
        """)
        with self.db:
            self.migrate()
            self.db.execute("UPDATE sends SET state='uncertain', detail='服务已重启，请核对聊天页' WHERE state='claimed'")
            self.db.execute("UPDATE sends SET state='expired', detail='服务已重启' WHERE state='queued'")
            self.db.execute("UPDATE groups SET routable=0")
            for group in self.db.execute("SELECT id FROM groups WHERE platform='wecom'").fetchall():
                self.reconcile_late_observations(group["id"])
        self.phones = {platform: {"seen": 0, "status": "等待手机连接", "ready": False}
                       for platform in PLATFORMS}

    def migrate(self):
        # Preserve old group IDs, snapshots and capture receipts for installed WeChat clients.
        self.db.execute("BEGIN IMMEDIATE")
        if "observed_seq" not in {row["name"] for row in self.db.execute("PRAGMA table_info(sends)")}:
            self.db.execute("ALTER TABLE sends ADD COLUMN observed_seq INTEGER")
        if "platform" not in {row["name"] for row in self.db.execute("PRAGMA table_info(groups)")}:
            self.db.execute("ALTER TABLE groups RENAME TO legacy_groups")
            self.db.execute("""CREATE TABLE groups (
                id TEXT PRIMARY KEY, platform TEXT NOT NULL, name TEXT NOT NULL, title TEXT NOT NULL,
                routable INTEGER NOT NULL DEFAULT 0, snapshot TEXT NOT NULL DEFAULT '[]',
                UNIQUE(platform,name))""")
            self.db.execute("""INSERT INTO groups(id,platform,name,title,routable,snapshot)
                            SELECT id,'weixin',name,title,routable,snapshot FROM legacy_groups""")
            self.db.execute("DROP TABLE legacy_groups")
        if "platform" not in {row["name"] for row in self.db.execute("PRAGMA table_info(captures)")}:
            self.db.execute("ALTER TABLE captures RENAME TO legacy_captures")
            self.db.execute("""CREATE TABLE captures (
                platform TEXT NOT NULL, id TEXT NOT NULL, PRIMARY KEY(platform,id))""")
            self.db.execute("INSERT INTO captures SELECT 'weixin',id FROM legacy_captures")
            self.db.execute("DROP TABLE legacy_captures")

    def log(self, source, detail, event_id=None):
        self.db.execute("INSERT OR IGNORE INTO event_log(event_id,created,source,detail) VALUES(?,?,?,?)",
                        (event_id, time.time(), source, detail[:400]))
        self.db.execute("DELETE FROM event_log WHERE seq <= (SELECT MAX(seq)-500 FROM event_log)")

    def expire(self, now):
        self.db.execute("UPDATE sends SET state='expired', detail='手机未及时领取' WHERE state='queued' AND deadline<?", (now,))
        self.db.execute("UPDATE sends SET state='uncertain', detail='未收到手机结果，请核对聊天页' WHERE state='claimed' AND deadline<?", (now,))

    def reconcile_late_observations(self, group_id):
        jobs = self.db.execute("""
            SELECT id,text,deadline,state,detail FROM sends
            WHERE group_id=? AND state IN ('claimed','uncertain','visible','observed')
        """, (group_id,)).fetchall()
        for job in jobs:
            if job["state"] != "uncertain" or job["detail"] != "等待界面超时":
                continue
            start = job["deadline"] - CLAIM_SECONDS
            candidates = self.db.execute("""
                SELECT seq,text,observed FROM messages
                WHERE group_id=? AND sender='本机账号' AND quality='ui_overlap' AND observed BETWEEN ? AND ?
            """, (group_id, start, start + LATE_OBSERVATION_SECONDS)).fetchall()
            matches = [row for row in candidates if row["text"].strip() == job["text"].strip()]
            if len(matches) != 1:
                continue
            row = matches[0]
            if any(other["id"] != job["id"] and other["text"].strip() == job["text"].strip()
                   and 0 <= row["observed"] - (other["deadline"] - CLAIM_SECONDS) <= LATE_OBSERVATION_SECONDS
                   for other in jobs):
                continue
            if self.db.execute("SELECT 1 FROM sends WHERE observed_seq=?", (row["seq"],)).fetchone():
                continue
            self.db.execute("""
                UPDATE sends SET state='observed', observed_seq=?,
                    detail='原回执超时，后续扫描读到匹配的本机出站文字'
                WHERE id=?
            """, (row["seq"], job["id"]))
            self.log("后端", f"任务 {job['id'][:8]} 后续匹配聊天记录 {row['seq']}")

    def capture(self, platform, page, now):
        self.db.execute("INSERT OR IGNORE INTO groups(id,platform,name,title) VALUES(?,?,?,?)",
                        (uuid.uuid4().hex, platform, page["name"], page["title"]))
        group = self.db.execute("SELECT * FROM groups WHERE platform=? AND name=?",
                                (platform, page["name"])).fetchone()
        if group["title"] != page["title"]:
            self.db.execute("UPDATE groups SET routable=0 WHERE id=?", (group["id"],))
            return
        previous = json.loads(group["snapshot"])
        rows = page["rows"]
        if not rows or rows == previous:
            return
        overlap = suffix_prefix(previous, rows)
        if overlap == len(rows) or (not overlap and
                any(previous[i:i + len(rows)] == rows for i in range(len(previous) - len(rows) + 1))):
            return
        quality = "baseline" if not previous else "ui_overlap" if overlap else "unlinked"
        self.db.executemany("INSERT INTO messages(group_id,sender,text,observed,quality) VALUES(?,?,?,?,?)",
                            [(group["id"], row["sender"], row["text"], now, quality) for row in rows[overlap:]])
        snapshot = (previous + rows[overlap:])[-120:] if platform == "wecom" and overlap else rows
        self.db.execute("UPDATE groups SET snapshot=? WHERE id=?", (json.dumps(snapshot, ensure_ascii=False), group["id"]))
        self.log("后端", f"「{group['name']}」{group['id'][:8]} 入库 {len(rows)-overlap} 条，{quality}")
        return group["id"]

    def sync(self, packet):
        platform = packet.get("platform", "weixin")
        if not isinstance(platform, str) or platform not in PLATFORMS:
            raise ValueError("设备平台无效")
        source = PLATFORMS[platform]
        events = packet.get("events", [])
        if (not isinstance(events, list) or len(events) > 30 or
                any(not isinstance(e, dict) or not isinstance(e.get("id"), str)
                    or not 0 < len(e["id"]) <= 128 or not isinstance(e.get("text"), str) for e in events)):
            raise ValueError("诊断事件格式无效")
        captures = packet.get("captures", [])
        if not isinstance(captures, list) or len(captures) > 8:
            raise ValueError("采集批次无效")
        for page in captures:
            if not isinstance(page, dict) or not all(isinstance(page.get(key), str) and 0 < len(page[key]) <= 240
                                                      for key in ("id", "name", "title")):
                raise ValueError("群采集格式无效")
            rows = page.get("rows")
            if not isinstance(rows, list) or len(rows) > 40:
                raise ValueError("消息列表无效")
            for row in rows:
                if (not isinstance(row, dict) or not isinstance(row.get("text"), str) or len(row["text"]) > 12000
                        or "sender" not in row or (row["sender"] is not None and
                                                   (not isinstance(row["sender"], str) or len(row["sender"]) > 240))):
                    raise ValueError("消息格式无效")
        routes = packet.get("routes")
        if routes is not None and (not isinstance(routes, dict) or len(routes) > 1000 or
                                   any(not isinstance(k, str) or not isinstance(v, str) for k, v in routes.items())):
            raise ValueError("群路由无效")
        completion = packet.get("completion")
        if completion is not None and (not isinstance(completion, dict)
                                       or completion.get("state") not in ("visible", "blocked", "uncertain")
                                       or not isinstance(completion.get("id"), str)
                                       or not isinstance(completion.get("detail"), str)):
            raise ValueError("发送结果无效")
        now = time.time()
        with self.lock, self.db:
            self.expire(now)
            status = str(packet.get("status", ""))[:200]
            phone = self.phones[platform]
            if now - phone["seen"] > 7:
                self.log(source, "收到手机连接")
            if not events and status and status != phone["status"]:
                self.log(source, status)
            for event in events:
                self.log(source, event["text"], platform + ":" + event["id"])
            ack = []
            affected_groups = set()
            for page in captures:
                fresh = self.db.execute("INSERT OR IGNORE INTO captures(platform,id) VALUES(?,?)", (platform, page["id"])).rowcount
                if fresh:
                    affected_groups.add(self.capture(platform, page, now))
                ack.append(page["id"])
            if routes is not None:
                self.db.execute("UPDATE groups SET routable=0 WHERE platform=?", (platform,))
                for name, title in routes.items():
                    self.db.execute("UPDATE groups SET routable=1 WHERE platform=? AND name=? AND title=?",
                                    (platform, name, title))
            complete_ack = None
            if completion:
                matched = self.db.execute("""
                    SELECT g.id FROM sends s JOIN groups g ON g.id=s.group_id
                    WHERE s.id=? AND g.platform=?
                """, (completion["id"], platform)).fetchone()
                if matched:
                    complete_ack = completion["id"]
                    detail = completion["detail"][:300]
                    changed = self.db.execute("""
                        UPDATE sends SET state=?,detail=? WHERE id=?
                        AND (state IN ('claimed','uncertain') OR (state='observed' AND ?='visible'))
                        AND (state!=? OR detail!=?)
                    """, (completion["state"], detail, complete_ack, completion["state"],
                          completion["state"], detail)).rowcount
                    if changed:
                        affected_groups.add(matched["id"])
                        self.log("后端", f"任务 {complete_ack[:8]} 回执 {completion['state']}。{detail}")
            if platform == "wecom":
                for group_id in affected_groups - {None}:
                    self.reconcile_late_observations(group_id)
            ready = packet.get("ready") is True
            job = None
            if ready:
                job = self.db.execute("""
                    SELECT s.id,s.text,g.name,g.title FROM sends s JOIN groups g ON g.id=s.group_id
                    WHERE s.state='queued' AND g.routable=1 AND g.platform=? ORDER BY s.created LIMIT 1
                """, (platform,)).fetchone()
                if job:
                    self.db.execute("UPDATE sends SET state='claimed',deadline=? WHERE id=?", (now+CLAIM_SECONDS, job["id"]))
                    self.log("后端", f"任务 {job['id'][:8]} 已下发至「{job['name']}」")
            self.phones[platform] = {"seen": now, "status": status, "ready": ready}
            return {"ack": ack, "event_ack": [event["id"] for event in events],
                    "completion_ack": complete_ack, "job": dict(job) if job else None}

    def enqueue(self, group, text, key):
        if not isinstance(group, str) or not isinstance(text, str) or not text.strip() or len(text) > 500 or "\0" in text:
            raise ValueError("请选择群，并填写 1 至 500 字的文字")
        if not isinstance(key, str) or not key or len(key) > 128:
            raise ValueError("需要有效的 request_id")
        now = time.time()
        with self.lock, self.db:
            self.expire(now)
            prior = self.db.execute("SELECT * FROM sends WHERE request_key=?", (key,)).fetchone()
            if prior:
                if (prior["group_id"], prior["text"]) != (group, text):
                    raise ValueError("相同请求编号的群或正文不同")
                return dict(prior)
            route = self.db.execute("SELECT routable,platform FROM groups WHERE id=?", (group,)).fetchone()
            if not route or not route["routable"]:
                raise ValueError("群暂时不能唯一定位")
            platform = route["platform"]
            if now - self.phones[platform]["seen"] > 7:
                raise ValueError("该平台手机未连接")
            if self.db.execute("""
                    SELECT 1 FROM sends s JOIN groups g ON g.id=s.group_id
                    WHERE g.platform=? AND s.state IN ('queued','claimed')
                    """, (platform,)).fetchone():
                raise ValueError("已有一条发送任务正在处理")
            task = uuid.uuid4().hex
            self.db.execute("INSERT INTO sends(id,group_id,text,state,created,deadline,request_key) VALUES(?,?,?,'queued',?,?,?)",
                            (task, group, text, now, now+75, key))
            self.log("后端", f"群 {group[:8]} 的任务 {task[:8]} 已排队")
            return {"id": task, "state": "queued"}

    def view(self, group="", after=None):
        with self.lock, self.db:
            self.expire(time.time())
            groups = [dict(row) for row in self.db.execute("SELECT id,platform,name,title,routable FROM groups ORDER BY platform,name")]
            if after is None:
                messages = list(self.db.execute("SELECT * FROM messages WHERE group_id=? ORDER BY seq DESC LIMIT 150", (group,)))
                messages.reverse()
            else:
                messages = self.db.execute("SELECT * FROM messages WHERE group_id=? AND seq>? ORDER BY seq LIMIT 150",
                                           (group, after)).fetchall()
            sends = self.db.execute("SELECT id,group_id,text,state,detail,observed_seq FROM sends WHERE group_id=? ORDER BY created DESC LIMIT 12",
                                    (group,)).fetchall()
            logs = self.db.execute("SELECT seq,created,source,detail FROM event_log ORDER BY seq DESC LIMIT 50").fetchall()
            now = time.time()
            phones = {platform: {"status": phone["status"], "online": now - phone["seen"] <= 7}
                      for platform, phone in self.phones.items()}
            selected_platform = next((entry["platform"] for entry in groups if entry["id"] == group), "weixin")
            phone = phones[selected_platform]
            return {"groups": groups, "messages": [dict(row) for row in messages],
                    "sends": [dict(row) for row in sends], "phone": phone["status"], "phones": phones,
                    "online": phone["online"], "logs": [dict(row) for row in logs]}


def make_server(host, port, config, *, tls=None):
    settings = json.loads(config.read_text())
    store = Store(config.parent / "weixin-web.sqlite3") if settings.get("token") else None
    gateway = Gateway(config.parent / "group-gateway-v1.sqlite3", settings.get("admin_token"))
    page = Path(__file__).with_name("v1.html").read_bytes()
    legacy_page = Path(__file__).with_name("index.html").read_bytes()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format, *args):
            pass

        def reply(self, status, body, mime="application/json; charset=utf-8", headers=None):
            data = b"" if status == 204 else body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            for key, value in (headers or {}).items():
                self.send_header(key, value)
            self.end_headers()
            self.wfile.write(data)

        def browser(self):
            actual_port = self.server.server_address[1]
            hosts = {f"127.0.0.1:{actual_port}", f"localhost:{actual_port}"}
            scheme = "https" if tls else "http"
            origin = self.headers.get("Origin")
            return (self.client_address[0] == "127.0.0.1" and self.headers.get("Host") in hosts
                    and (origin is None or origin in {f"{scheme}://{host}" for host in hosts}))

        def body(self):
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 2_000_000 or self.headers.get_content_type() != "application/json":
                raise ApiError(400, "请求格式无效")
            body = json.loads(self.rfile.read(length))
            if not isinstance(body, dict):
                raise ApiError(400, "请求格式无效")
            return body

        def v1(self, method, url):
            parts = url.path.strip("/").split("/")[1:]
            authorization = self.headers.get("Authorization", "")
            bearer = authorization[7:] if authorization.startswith("Bearer ") else ""
            body = self.body() if method == "POST" else {}
            browser = self.browser()
            with gateway.lock, gateway.db:
                if parts == ["admin", "session"] and method == "POST" and browser:
                    session = gateway.browser_session(bearer)
                    cookie = f"gateway_session={session}; Path=/v1; HttpOnly; SameSite=Strict; Max-Age=43200"
                    if tls:
                        cookie += "; Secure"
                    status, data = 200, {"ok": True}
                    response_headers = {"Set-Cookie": cookie}
                elif parts[0] in ("devices", "device-commands", "device-results"):
                    status, data = gateway.device(method, parts, body, bearer, self.headers)
                    response_headers = None
                else:
                    try:
                        cookie = SimpleCookie(self.headers.get("Cookie", "")).get("gateway_session")
                    except CookieError:
                        cookie = None
                    principal, grants = gateway.principal(bearer, cookie.value if cookie else "", browser)
                    if parts[0] == "admin":
                        if principal != "web":
                            raise ApiError(403, "需要后台会话")
                        status, data = gateway.admin(method, parts[1:], body)
                    else:
                        status, data = gateway.business(method, parts, parse_qs(url.query), body,
                                                        self.headers, principal, grants)
                    response_headers = None
            self.reply(status, data, headers=response_headers)

        def handle_request(self, method):
            try:
                url = urlsplit(self.path)
                if url.path.startswith("/v1/"):
                    return self.v1(method, url)
                if method == "POST" and url.path == "/device/sync" and store:
                    token = settings["token"]
                    if not hmac.compare_digest(self.headers.get("Authorization", "").encode(), ("Bearer "+token).encode()):
                        raise ApiError(403, "设备配对不匹配")
                    return self.reply(200, store.sync(self.body()))
                if not self.browser():
                    raise ApiError(403, "仅供本机访问")
                if method == "GET" and url.path == "/":
                    return self.reply(200, page, "text/html; charset=utf-8")
                if store and method == "GET" and url.path == "/legacy":
                    return self.reply(200, legacy_page, "text/html; charset=utf-8")
                if store and method == "GET" and url.path in ("/api/state", "/api/messages"):
                    query = parse_qs(url.query)
                    after = int(query.get("after", ["0"])[0]) if url.path == "/api/messages" else None
                    return self.reply(200, store.view(query.get("group_id", [""])[0], after))
                if store and method == "POST" and url.path == "/api/send":
                    body = self.body()
                    return self.reply(202, store.enqueue(body.get("group_id"), body.get("text"), body.get("request_id")))
                raise ApiError(404, "接口不存在")
            except ApiError as error:
                self.reply(error.status, {"error": str(error)})
            except (ValueError, TypeError) as error:
                self.reply(400, {"error": str(error)})

        def do_GET(self):
            self.handle_request("GET")

        def do_POST(self):
            self.handle_request("POST")

        def do_DELETE(self):
            self.handle_request("DELETE")

    server = ThreadingHTTPServer((host, port), Handler)
    server.gateway = gateway
    server.legacy_store = store
    if tls:
        server.socket = tls.wrap_socket(server.socket, server_side=True)
    return server


def serve(port, config, host, cert, key):
    tls = None
    if cert:
        import ssl
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.load_cert_chain(cert, key)
    with make_server(host, port, config, tls=tls) as server:
        scheme = "https" if tls else "http"
        print(f"群消息网关 {scheme}://127.0.0.1:{port}/", flush=True)
        server.serve_forever()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--cert")
    parser.add_argument("--key")
    args = parser.parse_args()
    if bool(args.cert) != bool(args.key):
        parser.error("--cert 和 --key 必须一起使用")
    os.umask(0o077)
    serve(args.port, args.config, args.host, args.cert, args.key)
