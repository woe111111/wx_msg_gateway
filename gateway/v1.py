"""Multi-device protocol for the group-message gateway."""
import hashlib
import hmac
import json
import re
import secrets
import sqlite3
import threading
import time
import uuid
from collections import deque
from datetime import datetime

SESSION_TTL = 30
LEASE_SECONDS = 100
SEND_TTL = 600
CLIENT_ID = re.compile(r"[A-Za-z0-9_-]{1,64}")
SCOPES = {"groups:read", "messages:read", "messages:send"}
FINAL = {"confirmed", "observed", "blocked", "expired", "uncertain"}


class ApiError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def string(value, label, limit=240):
    if not isinstance(value, str) or not value.strip() or len(value) > limit or "\0" in value:
        raise ApiError(400, f"{label} 格式无效")
    return value


def overlap(left, right):
    return next((n for n in range(min(len(left), len(right)), 0, -1) if left[-n:] == right[:n]), 0)


def contains_rows(haystack, needle):
    return any(haystack[i:i + len(needle)] == needle for i in range(len(haystack) - len(needle) + 1))


def alert_text(body):
    required = {"sn": "SN", "problem": "问题", "started_at": "开始时间", "suggestion": "处理建议"}
    optional = {"severity": "级别", "source": "来源", "alert_id": "告警编号", "detail": "判断依据",
                "status": "状态", "recovered_at": "恢复时间"}
    if set(body) - required.keys() - optional.keys():
        raise ApiError(400, "存在未知告警字段")
    for field, label in required.items():
        string(body.get(field), label, 500)
    for field, label in optional.items():
        if field in body:
            string(body[field], label, 500)
    status = body.get("status", "triggered")
    if status not in ("triggered", "recovered") or ("recovered_at" in body) != (status == "recovered"):
        raise ApiError(400, "告警状态与恢复时间不匹配")
    times = []
    for key in ("started_at", "recovered_at"):
        if key in body:
            try:
                value = datetime.fromisoformat(body[key].replace("Z", "+00:00"))
                if value.utcoffset() is None:
                    raise ValueError
                times.append(value)
            except ValueError:
                raise ApiError(400, "告警时间须包含日期、时间和时区") from None
    if len(times) == 2 and times[1] < times[0]:
        raise ApiError(400, "恢复时间早于开始时间")
    level = body.get("severity", "")
    heading = "告警恢复" if status == "recovered" else "告警"
    lines = [f"【{level + ' ' if level else ''}{heading}】{body['problem']}"]
    fields = {**required, **optional}
    lines.extend(f"{fields[key]}：{body[key]}" for key in
                 ("sn", "started_at", "recovered_at", "detail", "suggestion", "source", "alert_id") if key in body)
    return "\n".join(lines)


class Gateway:
    def __init__(self, path, admin_token=None):
        self.admin_token = admin_token
        self.lock = threading.RLock()
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.executescript("""
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS clients (
                client_id TEXT PRIMARY KEY, label TEXT NOT NULL, key_hash TEXT,
                instance TEXT, session TEXT, epoch INTEGER NOT NULL DEFAULT 0,
                seen REAL NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT '',
                admission TEXT NOT NULL DEFAULT 'registered');
            CREATE TABLE IF NOT EXISTS api_keys (
                key_id TEXT PRIMARY KEY, label TEXT NOT NULL, key_hash TEXT UNIQUE,
                grants TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS groups (
                group_id TEXT PRIMARY KEY, client_id TEXT NOT NULL, name TEXT NOT NULL, title TEXT NOT NULL,
                seen REAL, history_status TEXT NOT NULL DEFAULT 'pending',
                head TEXT NOT NULL DEFAULT '[]', oldest TEXT NOT NULL DEFAULT '[]', next_offset INTEGER NOT NULL DEFAULT 1,
                routable INTEGER NOT NULL DEFAULT 0, UNIQUE(client_id,name));
            CREATE TABLE IF NOT EXISTS captures (
                client_id TEXT NOT NULL, capture_id TEXT NOT NULL, payload_hash TEXT NOT NULL,
                group_id TEXT NOT NULL, rows TEXT NOT NULL, sweep_id TEXT, older_than TEXT,
                PRIMARY KEY(client_id,capture_id));
            CREATE TABLE IF NOT EXISTS messages (
                group_id TEXT NOT NULL, offset INTEGER NOT NULL, message_id TEXT NOT NULL UNIQUE,
                sender TEXT, text TEXT NOT NULL, direction TEXT NOT NULL, observed_at REAL NOT NULL,
                backfilled INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(group_id,offset));
            CREATE TABLE IF NOT EXISTS tasks (
                task_id TEXT PRIMARY KEY, group_id TEXT NOT NULL, client_id TEXT NOT NULL,
                principal TEXT NOT NULL, idem TEXT NOT NULL, kind TEXT NOT NULL, payload TEXT NOT NULL,
                text TEXT NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '',
                created_at REAL NOT NULL, expires_at REAL NOT NULL, claimed_at REAL,
                session TEXT, lease_until REAL, reserved_at REAL, message_id TEXT UNIQUE, offset INTEGER,
                UNIQUE(principal,client_id,group_id,idem));
            CREATE INDEX IF NOT EXISTS queue ON tasks(client_id,status,created_at);
            CREATE TABLE IF NOT EXISTS device_events (
                client_id TEXT NOT NULL, event_id TEXT NOT NULL, payload_hash TEXT NOT NULL,
                PRIMARY KEY(client_id,event_id));
        """)
        with self.db:
            for table, column, declaration in (
                ("clients", "admission", "TEXT NOT NULL DEFAULT 'registered'"),
                ("groups", "oldest", "TEXT NOT NULL DEFAULT '[]'"),
                ("captures", "sweep_id", "TEXT"),
                ("captures", "older_than", "TEXT"),
                ("messages", "backfilled", "INTEGER NOT NULL DEFAULT 0"),
                ("api_keys", "key_ciphertext", "TEXT"),
            ):
                if not any(row["name"] == column for row in self.db.execute(f"PRAGMA table_info({table})")):
                    self.db.execute(f"ALTER TABLE {table} ADD COLUMN {column} {declaration}")
                    if column == "admission":
                        self.db.execute("UPDATE clients SET session=NULL,seen=0,epoch=epoch+1")
        self.web_sessions = {}

    def one(self, sql, params=()):
        return self.db.execute(sql, params).fetchone()

    def expire(self, now):
        self.db.execute("UPDATE tasks SET status='expired',detail='等待领取超时' WHERE status='queued' AND expires_at<=?", (now,))
        self.db.execute("""
            UPDATE tasks SET status=CASE WHEN reserved_at IS NULL THEN 'blocked' ELSE 'uncertain' END,
                detail='设备执行租约到期' WHERE status='executing' AND lease_until<=?
        """, (now,))

    def browser_session(self, token):
        if not self.admin_token or not hmac.compare_digest(token.encode(), self.admin_token.encode()):
            raise ApiError(401, "后台凭据无效")
        now = time.time()
        self.web_sessions = {key: deadline for key, deadline in self.web_sessions.items() if deadline > now}
        session = secrets.token_urlsafe(32)
        self.web_sessions[digest(session)] = now + 12 * 3600
        return session

    def principal(self, bearer, cookie, browser):
        if browser and cookie and self.web_sessions.get(digest(cookie), 0) > time.time():
            return "web", None
        key = self.one("SELECT key_id,grants FROM api_keys WHERE key_hash=?", (digest(bearer),)) if bearer else None
        if not key:
            raise ApiError(401, "接口 Key 或后台会话无效")
        return key["key_id"], json.loads(key["grants"])

    @staticmethod
    def permits(grants, client, group, scope):
        if grants is None:
            return True
        return any(item["client_id"] == client and scope in item["scopes"]
                   and (item["group_ids"] == "*" or (group is not None and group in item["group_ids"]))
                   for item in grants)

    def group(self, client, group_id):
        row = self.one("SELECT * FROM groups WHERE client_id=? AND group_id=?", (client, group_id))
        if not row:
            raise ApiError(404, "指定客户端下没有此群")
        return row

    def admin(self, method, parts, body):
        now = time.time()
        if parts == ["clients"]:
            if method == "GET":
                rows = self.db.execute("SELECT client_id,label,seen,status,admission,key_hash IS NOT NULL AS enabled FROM clients ORDER BY client_id")
                return 200, {"clients": [dict(row, online=bool(row["enabled"] and row["admission"] == "approved"
                                and now - row["seen"] < SESSION_TTL)) for row in rows]}
            if method == "POST":
                client = string(body.get("client_id"), "client_id", 64)
                if not CLIENT_ID.fullmatch(client):
                    raise ApiError(400, "client_id 仅支持字母、数字、短横线和下划线")
                label = string(body.get("label", client), "设备名称")
                key = secrets.token_urlsafe(32)
                try:
                    self.db.execute("INSERT INTO clients(client_id,label,key_hash) VALUES(?,?,?)",
                                    (client, label, digest(key)))
                except sqlite3.IntegrityError:
                    raise ApiError(409, "client_id 已存在") from None
                return 201, {"client_id": client, "registration_key": key}
        if method == "POST" and len(parts) == 3 and parts[0] == "clients" and parts[2] == "approve":
            device = self.one("SELECT admission FROM clients WHERE client_id=?", (parts[1],))
            if not device:
                raise ApiError(404, "设备不存在")
            if device["admission"] not in ("pending", "approved"):
                raise ApiError(409, "请先在安卓端申请接入")
            self.db.execute("UPDATE clients SET admission='approved' WHERE client_id=?", (parts[1],))
            return 200, {"client_id": parts[1], "admission": "approved"}
        if len(parts) >= 3 and parts[0] == "clients" and parts[2] == "key":
            if (method, parts[3:]) in (("DELETE", []), ("POST", ["rotate"])):
                client = parts[1]
                key = secrets.token_urlsafe(32) if method == "POST" else None
                updated = self.db.execute("""
                    UPDATE clients SET key_hash=?,session=NULL,instance=NULL,seen=0,epoch=epoch+1,admission=? WHERE client_id=?
                """, (digest(key) if key else None, "registered" if key else "revoked", client))
                if not updated.rowcount:
                    raise ApiError(404, "设备不存在")
                self.end_execution(client, "设备凭据已更新")
                return (200, {"client_id": client, "registration_key": key}) if key else (204, None)
        if parts == ["api-keys"]:
            if method == "GET":
                rows = self.db.execute("SELECT key_id,label,grants,key_hash IS NOT NULL AS enabled FROM api_keys ORDER BY label,key_id")
                return 200, {"keys": [dict(row, grants=json.loads(row["grants"])) for row in rows]}
            if method == "POST":
                label = string(body.get("label"), "应用名称")
                grants = body.get("grants")
                if not isinstance(grants, list) or not 1 <= len(grants) <= 100:
                    raise ApiError(400, "请指定客户端和权限")
                for grant in grants:
                    if not isinstance(grant, dict):
                        raise ApiError(400, "授权格式无效")
                    client = string(grant.get("client_id"), "client_id", 64)
                    if not self.one("SELECT 1 FROM clients WHERE client_id=?", (client,)):
                        raise ApiError(404, "授权客户端不存在")
                    scopes = grant.get("scopes")
                    if not isinstance(scopes, list) or not scopes or any(not isinstance(s, str) or s not in SCOPES for s in scopes):
                        raise ApiError(400, "授权权限无效")
                    groups = grant.get("group_ids")
                    if groups != "*":
                        if not isinstance(groups, list) or not groups:
                            raise ApiError(400, "请选择授权群")
                        for group_id in groups:
                            self.group(client, string(group_id, "group_id"))
                key_id, key = uuid.uuid4().hex, secrets.token_urlsafe(32)
                self.db.execute("INSERT INTO api_keys(key_id,label,key_hash,grants) VALUES(?,?,?,?)",
                                (key_id, label, digest(key), encoded(grants)))
                return 201, {"key_id": key_id, "api_key": key}
        if len(parts) >= 2 and parts[0] == "api-keys":
            if method == "DELETE" and parts[2:] == ["record"]:
                deleted = self.db.execute("DELETE FROM api_keys WHERE key_id=? AND key_hash IS NULL", (parts[1],))
                if deleted.rowcount:
                    return 204, None
                if self.one("SELECT 1 FROM api_keys WHERE key_id=?", (parts[1],)):
                    raise ApiError(409, "请先撤销接口 Key")
                raise ApiError(404, "接口 Key 不存在")
            if (method, parts[2:]) in (("DELETE", []), ("POST", ["rotate"])):
                key = secrets.token_urlsafe(32) if method == "POST" else None
                updated = self.db.execute("UPDATE api_keys SET key_hash=?,key_ciphertext=NULL WHERE key_id=?",
                                          (digest(key) if key else None, parts[1]))
                if not updated.rowcount:
                    raise ApiError(404, "接口 Key 不存在")
                return (200, {"key_id": parts[1], "api_key": key}) if key else (204, None)
        raise ApiError(404, "接口不存在")

    def end_execution(self, client, detail):
        self.db.execute("""
            UPDATE tasks SET status=CASE WHEN reserved_at IS NULL THEN 'blocked' ELSE 'uncertain' END,
                detail=? WHERE client_id=? AND status='executing'
        """, (detail, client))

    def device(self, method, parts, body, bearer, headers):
        client = headers.get("X-Client-Id") or body.get("client_id")
        if not isinstance(client, str) or not CLIENT_ID.fullmatch(client) or body.get("client_id", client) != client:
            raise ApiError(400, "client_id 无效")
        device = self.one("SELECT * FROM clients WHERE client_id=?", (client,))
        if not bearer or not device or not device["key_hash"] or not hmac.compare_digest(digest(bearer), device["key_hash"]):
            raise ApiError(401, "注册 Key 与客户端不匹配")
        now = time.time()
        if method == "POST" and parts == ["devices", "session"]:
            instance = string(body.get("instance_id"), "instance_id")
            if device["admission"] != "approved":
                self.db.execute("UPDATE clients SET admission='pending',status='等待 Web 审批' WHERE client_id=?", (client,))
                return 202, {"status": "pending_approval"}
            if device["session"] and device["instance"] != instance and now - device["seen"] < SESSION_TTL:
                raise ApiError(409, "该客户端已有活动会话")
            session, epoch = device["session"], device["epoch"]
            if not session or device["instance"] != instance or now - device["seen"] >= SESSION_TTL:
                self.end_execution(client, "设备重新连接，请核对旧任务")
                session, epoch = secrets.token_urlsafe(24), epoch + 1
            self.db.execute("UPDATE clients SET instance=?,session=?,epoch=?,seen=? WHERE client_id=?",
                            (instance, session, epoch, now, client))
            return 200, {"device_session_id": session, "lease_epoch": epoch}
        if device["admission"] != "approved":
            raise ApiError(403, "设备尚未通过 Web 审批")
        if (not device["session"] or now - device["seen"] >= SESSION_TTL
                or headers.get("X-Device-Session") != device["session"]
                or headers.get("X-Lease-Epoch") != str(device["epoch"])):
            raise ApiError(409, "设备会话已失效，请重新注册")
        self.db.execute("UPDATE clients SET seen=? WHERE client_id=?", (now, client))
        if method == "POST" and parts == ["devices", "heartbeat"]:
            self.db.execute("UPDATE clients SET status=? WHERE client_id=?", (str(body.get("status", ""))[:200], client))
            return 200, {"ok": True}
        if method == "GET" and parts == ["devices", "config"]:
            rows = self.db.execute("SELECT group_id,name,title,head,history_status FROM groups WHERE client_id=?", (client,))
            return 200, {"config_revision": 1, "groups": [
                dict(row, head=json.loads(row["head"])[-12:]) for row in rows]}
        if method == "POST" and parts == ["device-results"]:
            return 200, self.device_results(client, body, now)
        if method == "POST" and parts == ["device-commands", "claim"]:
            self.expire(now)
            if body.get("ready") is not True:
                return 204, None
            task = self.one("SELECT * FROM tasks WHERE client_id=? AND status='executing'", (client,))
            if task:
                if task["session"] != device["session"] or task["reserved_at"] is not None:
                    return 204, None
            else:
                task = self.one("""
                    SELECT tasks.* FROM tasks JOIN groups USING(group_id)
                    WHERE tasks.client_id=? AND tasks.status='queued' AND groups.routable=1
                    ORDER BY tasks.created_at,tasks.task_id LIMIT 1
                """, (client,))
                if not task:
                    return 204, None
                self.db.execute("""
                    UPDATE tasks SET status='executing',session=?,lease_until=?,claimed_at=? WHERE task_id=?
                """, (device["session"], now + LEASE_SECONDS, now, task["task_id"]))
                task = self.one("SELECT * FROM tasks WHERE task_id=?", (task["task_id"],))
            group = self.group(client, task["group_id"])
            return 200, {"command_id": task["task_id"], "task_id": task["task_id"], "type": "SEND_TEXT",
                         "client_id": client, "group_id": group["group_id"], "name": group["name"],
                         "title": group["title"], "text": task["text"],
                         "body_digest": digest(task["text"]), "lease_remaining_ms": int((task["lease_until"] - now) * 1000)}
        if method == "POST" and len(parts) == 3 and parts[0] == "device-commands":
            task = self.one("SELECT * FROM tasks WHERE task_id=? AND client_id=?", (parts[1], client))
            if not task or task["group_id"] != body.get("group_id"):
                raise ApiError(404, "命令与设备或群不匹配")
            if (task["status"] != "executing" or task["session"] != device["session"]
                    or now >= task["lease_until"]):
                raise ApiError(409, "命令已失效")
            if parts[2] == "renew":
                self.db.execute("UPDATE tasks SET lease_until=? WHERE task_id=?", (now + LEASE_SECONDS, task["task_id"]))
                return 200, {"lease_remaining_ms": LEASE_SECONDS * 1000}
            if parts[2] == "click-reservation":
                group = self.group(client, task["group_id"])
                if (not group["routable"] or group["title"] != body.get("title")
                        or body.get("body_digest") != digest(task["text"])):
                    raise ApiError(409, "群路由或正文核对失败")
                if task["reserved_at"] is not None:
                    return 200, {"may_click": False}
                self.db.execute("UPDATE tasks SET reserved_at=? WHERE task_id=?", (now, task["task_id"]))
                return 200, {"may_click": True}
        raise ApiError(404, "接口不存在")

    def device_results(self, client, body, now):
        captures = body.get("captures", [])
        if not isinstance(captures, list) or len(captures) > 8:
            raise ApiError(400, "采集批次无效")
        ack, bindings = [], {}
        for page in captures:
            if not isinstance(page, dict):
                raise ApiError(400, "采集格式无效")
            capture_id = string(page.get("capture_id", page.get("id")), "capture_id")
            group_id = self.capture(client, capture_id, page, now)
            ack.append(capture_id)
            bindings[page["name"]] = group_id
        routes = body.get("routes")
        if routes is not None:
            if not isinstance(routes, dict) or len(routes) > 1000:
                raise ApiError(400, "群路由无效")
            self.db.execute("UPDATE groups SET routable=0 WHERE client_id=?", (client,))
            for name, title in routes.items():
                self.db.execute("UPDATE groups SET routable=1 WHERE client_id=? AND name=? AND title=?",
                                (client, string(name, "群名"), string(title, "群标题")))
        completion_ack = None
        completion = body.get("completion")
        if completion is not None:
            if not isinstance(completion, dict):
                raise ApiError(400, "回执格式无效")
            completion_ack = self.complete(client, completion)
        events = body.get("events", [])
        if not isinstance(events, list) or len(events) > 30:
            raise ApiError(400, "设备事件格式无效")
        event_ack = []
        for event in events:
            if not isinstance(event, dict):
                raise ApiError(400, "设备事件格式无效")
            event_id = string(event.get("id"), "event_id")
            string(event.get("text"), "事件正文", 1000)
            self.remember_event(client, event_id, encoded(event))
            event_ack.append(event_id)
        return {"ack": ack, "bindings": bindings, "completion_ack": completion_ack, "event_ack": event_ack}

    def remember_event(self, client, event, value):
        content = digest(value)
        prior = self.one("SELECT payload_hash FROM device_events WHERE client_id=? AND event_id=?", (client, event))
        if prior:
            if prior["payload_hash"] != content:
                raise ApiError(409, "事件编号重复但内容不同")
            return False
        self.db.execute("INSERT INTO device_events VALUES(?,?,?)", (client, event, content))
        return True

    def capture(self, client, capture_id, page, now):
        name = string(page.get("name"), "群名")
        title = string(page.get("title"), "群标题")
        content = digest(encoded(page))
        prior = self.one("SELECT * FROM captures WHERE client_id=? AND capture_id=?", (client, capture_id))
        if prior:
            if prior["payload_hash"] != content:
                raise ApiError(409, "采集编号重复但内容不同")
            return prior["group_id"]
        rows = page.get("rows")
        if not isinstance(rows, list) or len(rows) > 300:
            raise ApiError(400, "消息窗口无效")
        normalized = []
        for row in rows:
            if not isinstance(row, dict):
                raise ApiError(400, "消息格式无效")
            text = string(row.get("text"), "消息正文", 12000)
            sender = row.get("sender")
            if sender is not None:
                string(sender, "发送人")
            direction = row.get("direction", "unknown")
            if direction not in ("incoming", "outgoing", "unknown"):
                raise ApiError(400, "消息方向无效")
            normalized.append({"sender": sender, "text": text, "direction": direction})
        group = self.one("SELECT * FROM groups WHERE client_id=? AND name=?", (client, name))
        if not group:
            if page.get("group_id"):
                raise ApiError(404, "指定客户端下没有此群")
            group_id = uuid.uuid4().hex
            self.db.execute("INSERT INTO groups(group_id,client_id,name,title) VALUES(?,?,?,?)", (group_id, client, name, title))
            group = self.group(client, group_id)
        if page.get("group_id") and group["group_id"] != page["group_id"]:
            raise ApiError(404, "指定客户端下群绑定不匹配")
        group_id = group["group_id"]
        sweep = page.get("sweep_id")
        older_than = page.get("older_than")
        end = page.get("history_status")
        if end not in (None, "complete", "anchored", "gap"):
            raise ApiError(400, "采集状态无效")
        if sweep is not None:
            sweep = string(sweep, "sweep_id")
            if older_than is None and sweep != capture_id:
                raise ApiError(400, "采集首屏编号与轮次不匹配")
            if older_than is not None:
                older_than = string(older_than, "older_than")
                if not self.one("SELECT 1 FROM captures WHERE client_id=? AND group_id=? AND capture_id=? AND sweep_id=?",
                                (client, group_id, older_than, sweep)):
                    raise ApiError(409, "上一屏采集尚未确认")
        elif older_than is not None:
            raise ApiError(400, "缺少采集轮次")
        self.db.execute("""
            INSERT INTO captures(client_id,capture_id,payload_hash,group_id,rows,sweep_id,older_than)
            VALUES(?,?,?,?,?,?,?)
        """, (client, capture_id, content, group_id, encoded(normalized), sweep, older_than))
        if title != group["title"]:
            self.db.execute("UPDATE groups SET routable=0,history_status='gap' WHERE group_id=?", (group_id,))
            return group_id
        if sweep is not None and end is None:
            return group_id
        if sweep is not None:
            normalized, linked = self.assemble(client, group_id, capture_id)
            if not linked:
                end = "gap"
        self.commit_window(group, normalized, end, now)
        return group_id

    def assemble(self, client, group_id, capture_id):
        windows = []
        while capture_id:
            row = self.one("SELECT rows,older_than FROM captures WHERE client_id=? AND group_id=? AND capture_id=?",
                           (client, group_id, capture_id))
            windows.append(json.loads(row["rows"]))
            capture_id = row["older_than"]
        current = windows.pop()
        parts = deque([current])
        prefix = current[:300]
        linked = True
        while windows:
            older = windows.pop()
            boundary = overlap(older, prefix)
            if not boundary:
                linked = False
                break
            before = older[:-boundary]
            parts.appendleft(before)
            prefix = (before + prefix)[:300]
        return [message for part in parts for message in part], linked

    @staticmethod
    def unique_anchor(rows, anchor):
        matches = [i for i in range(len(rows) - len(anchor) + 1) if rows[i:i+len(anchor)] == anchor]
        return matches[0] if len(matches) == 1 else None

    def commit_window(self, group, rows, end, now):
        group_id = group["group_id"]
        head, oldest = json.loads(group["head"]), json.loads(group["oldest"])
        state = group["history_status"]
        extra_old, extra_new = [], []
        initial = not head
        if not head:
            extra_new = rows
            head, oldest = rows[-300:], rows[:12]
        elif rows:
            anchor = head[-12:]
            position = self.unique_anchor(rows, anchor)
            if position is not None:
                extra_new = rows[position + len(anchor):]
            elif not contains_rows(head, rows):
                boundary = overlap(head, rows)
                if boundary:
                    extra_new = rows[boundary:]
                else:
                    end = "gap"
            if end == "complete" and oldest:
                start = self.unique_anchor(rows, oldest)
                if start is None:
                    end = "gap"
                else:
                    extra_old = rows[:start]
                    if extra_old:
                        oldest = (extra_old + oldest)[:12]
            head = (head + extra_new)[-300:]
        if end == "gap":
            state = "gap"
        elif end == "complete":
            state = "complete"
        pending = extra_old + extra_new
        offset = group["next_offset"]
        self.db.executemany("""
            INSERT INTO messages(group_id,offset,message_id,sender,text,direction,observed_at,backfilled)
            VALUES(?,?,?,?,?,?,?,?)
        """, [
            (group_id, offset + i, uuid.uuid4().hex, row["sender"], row["text"], row["direction"], now,
             int(initial or i < len(extra_old)))
            for i, row in enumerate(pending)])
        self.db.execute("UPDATE groups SET head=?,oldest=?,seen=?,history_status=?,next_offset=? WHERE group_id=?",
                        (encoded(head), encoded(oldest), now, state, offset + len(pending), group_id))
        if pending:
            self.reconcile_observed(group_id)

    def reconcile_observed(self, group_id):
        tasks = self.db.execute("""
            SELECT * FROM tasks WHERE group_id=? AND status='uncertain' AND reserved_at IS NOT NULL
        """, (group_id,)).fetchall()
        for task in tasks:
            evidence = self.outgoing(task)
            if evidence:
                self.db.execute("UPDATE tasks SET status='observed',message_id=?,offset=? WHERE task_id=?",
                                (evidence["message_id"], evidence["offset"], task["task_id"]))

    def outgoing(self, task):
        rows = self.db.execute("""
            SELECT m.* FROM messages m WHERE m.group_id=? AND m.direction='outgoing' AND m.backfilled=0
            AND m.observed_at BETWEEN ? AND ? AND NOT EXISTS (
                SELECT 1 FROM tasks t WHERE t.message_id=m.message_id)
        """, (task["group_id"], task["reserved_at"], task["reserved_at"] + 300))
        matches = [row for row in rows if row["text"].strip() == task["text"].strip()]
        if len(matches) != 1:
            return None
        competing = self.db.execute("""
            SELECT text FROM tasks WHERE group_id=? AND task_id!=? AND reserved_at BETWEEN ? AND ?
        """, (task["group_id"], task["task_id"], matches[0]["observed_at"] - 300, matches[0]["observed_at"]))
        if any(row["text"].strip() == task["text"].strip() for row in competing):
            return None
        return matches[0]

    def complete(self, client, result):
        task_id = string(result.get("id"), "command_id")
        event = string(result.get("event_id"), "event_id")
        task = self.one("SELECT * FROM tasks WHERE task_id=? AND client_id=?", (task_id, client))
        if not task or task["group_id"] != result.get("group_id"):
            raise ApiError(404, "回执与设备或群不匹配")
        status = result.get("state")
        if status not in ("confirmed", "blocked", "uncertain"):
            raise ApiError(400, "回执状态无效")
        if task["status"] in ("queued", "expired") or (status == "confirmed" and task["reserved_at"] is None):
            raise ApiError(409, "命令状态与回执不匹配")
        if not self.remember_event(client, event, encoded(result)):
            return task_id
        if task["status"] in ("executing", "uncertain") or (task["status"] == "observed" and status == "confirmed"):
            if status == "blocked" and task["reserved_at"] is not None:
                status = "uncertain"
            evidence = self.outgoing(task) if status == "confirmed" else None
            self.db.execute("""
                UPDATE tasks SET status=?,detail=?,message_id=COALESCE(?,message_id),offset=COALESCE(?,offset)
                WHERE task_id=?
            """, (status, str(result.get("detail", ""))[:400], evidence["message_id"] if evidence else None,
                  evidence["offset"] if evidence else None, task_id))
            if status == "uncertain":
                self.reconcile_observed(task["group_id"])
        return task_id

    def business(self, method, parts, query, body, headers, principal, grants):
        if len(parts) < 3 or parts[0] != "clients" or parts[2] != "groups":
            raise ApiError(404, "接口不存在")
        client = parts[1]
        group_id = parts[3] if len(parts) > 3 else None
        tail = parts[4:]
        scope = "groups:read"
        if tail and tail[0] == "messages":
            scope = "messages:read"
        elif tail and tail[0] in ("send-requests", "alert-send-requests"):
            scope = "messages:send"
        if group_id is None and method == "GET":
            if grants is not None and not any(g["client_id"] == client and scope in g["scopes"] for g in grants):
                raise ApiError(403, "接口 Key 无此客户端的查群权限")
            if not self.one("SELECT 1 FROM clients WHERE client_id=?", (client,)):
                raise ApiError(404, "客户端不存在")
            rows = self.db.execute("SELECT * FROM groups WHERE client_id=? ORDER BY group_id", (client,))
            limit = self.number(query, "limit", 100, 1, 200)
            after = query.get("after_group_id", [""])[0]
            groups = [self.group_info(row) for row in rows if row["group_id"] > after
                      and self.permits(grants, client, row["group_id"], scope)]
            return 200, {"groups": groups[:limit], "next_after_group_id": groups[limit - 1]["group_id"]
                         if len(groups) > limit else None}
        if not group_id:
            raise ApiError(404, "群不存在")
        group = self.group(client, group_id)
        if not self.permits(grants, client, group_id, scope):
            raise ApiError(403, "接口 Key 无该群的操作权限")
        if method == "GET" and not tail:
            return 200, self.group_info(group)
        if method == "GET" and tail in (["messages"], ["messages", "recent"]):
            if tail[-1] == "recent":
                rows = list(reversed(self.db.execute(
                    "SELECT * FROM messages WHERE group_id=? ORDER BY offset DESC LIMIT 30", (group_id,)).fetchall()))
            else:
                after = self.number(query, "after_offset", None, 0, (1 << 63) - 1)
                limit = self.number(query, "limit", None, 1, 1000)
                rows = self.db.execute(
                    "SELECT * FROM messages WHERE group_id=? AND offset>? ORDER BY offset LIMIT ?", (group_id, after, limit))
            return 200, {"messages": [dict(row, client_id=client) for row in rows]}
        if method == "POST" and tail in (["send-requests"], ["alert-send-requests"]):
            return self.enqueue(client, group, tail[0], body, headers.get("Idempotency-Key"), principal)
        if method == "GET" and tail == ["send-requests"] and grants is None:
            self.expire(time.time())
            rows = self.db.execute("SELECT * FROM tasks WHERE client_id=? AND group_id=? ORDER BY created_at DESC LIMIT 20",
                                   (client, group_id))
            return 200, {"tasks": [dict(self.task_info(row), text=row["text"]) for row in rows]}
        if method == "GET" and len(tail) == 2 and tail[0] == "send-requests":
            self.expire(time.time())
            task = self.one("SELECT * FROM tasks WHERE client_id=? AND group_id=? AND task_id=?", (client, group_id, tail[1]))
            if not task or (grants is not None and task["principal"] != principal):
                raise ApiError(404, "发送任务不存在")
            return 200, self.task_info(task)
        raise ApiError(404, "接口不存在")

    @staticmethod
    def number(query, name, default, low, high):
        try:
            value = int(query[name][0]) if name in query else default
            if value is not None and low <= value <= high:
                return value
        except ValueError:
            pass
        raise ApiError(400, f"{name} 格式或范围无效")

    def group_info(self, group):
        client = self.one("SELECT seen,key_hash FROM clients WHERE client_id=?", (group["client_id"],))
        return {key: group[key] for key in ("client_id", "group_id", "name", "title", "history_status")} | {
            "last_synced_at": group["seen"], "routable": bool(group["routable"]),
            "online": bool(client["key_hash"] and time.time() - client["seen"] < SESSION_TTL)}

    def enqueue(self, client, group, kind, body, idem, principal):
        idem = string(idem, "Idempotency-Key", 128)
        payload = encoded(body)
        group_id = group["group_id"]
        now = time.time()
        self.expire(now)
        prior = self.one("SELECT * FROM tasks WHERE principal=? AND client_id=? AND group_id=? AND idem=?",
                         (principal, client, group_id, idem))
        if prior:
            if prior["kind"] != kind or prior["payload"] != payload:
                raise ApiError(409, "同一个幂等 Key 对应的发送内容不同")
            return 200, self.task_info(prior)
        if kind == "alert-send-requests":
            text = alert_text(body)
        else:
            if set(body) != {"text"}:
                raise ApiError(400, "普通回复只接受 text")
            text = body["text"]
        string(text, "发送正文", 500)
        if not group["routable"]:
            raise ApiError(409, "群入口当前未确认")
        task_id = uuid.uuid4().hex
        self.db.execute("""
            INSERT INTO tasks(task_id,group_id,client_id,principal,idem,kind,payload,text,status,created_at,expires_at)
            VALUES(?,?,?,?,?,?,?,?,'queued',?,?)
        """, (task_id, group_id, client, principal, idem, kind, payload, text, now, now + SEND_TTL))
        return 202, self.task_info(self.one("SELECT * FROM tasks WHERE task_id=?", (task_id,)))

    @staticmethod
    def task_info(task):
        return {key: task[key] for key in ("task_id", "client_id", "group_id", "status", "detail",
                                          "message_id", "offset", "created_at", "expires_at")}
