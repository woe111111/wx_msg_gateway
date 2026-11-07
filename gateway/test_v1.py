import concurrent.futures
from http.client import HTTPConnection
import json
import os
from pathlib import Path
import sqlite3
import subprocess
from tempfile import TemporaryDirectory
import unittest


class MultiClientTest(unittest.TestCase):
    def setUp(self):
        work = TemporaryDirectory()
        self.addCleanup(work.cleanup)
        self.config = Path(work.name) / "gateway.json"
        self.config.write_text(json.dumps({"admin_token": "test-admin-key"}))
        default_binary = Path(__file__).resolve().parent / "rust/target/release/wecom-gateway"
        self.binary = Path(os.environ.get("GATEWAY_TEST_BINARY", default_binary))
        if not self.binary.is_file():
            self.fail(f"请先构建 Rust 网关，或用 GATEWAY_TEST_BINARY 指定二进制路径：{self.binary}")
        self.port, self.device_port = self.start_binary(self.config)
        self.admin = {"Authorization": "Bearer test-admin-key"}
        self.login()

    def start_binary(self, config):
        process = subprocess.Popen([self.binary, "--host", "127.0.0.1", "--port", "0",
                                    "--device-port", "0", "--config", str(config)],
                                   stdout=subprocess.PIPE, text=True)
        self.addCleanup(process.stdout.close)
        self.addCleanup(process.wait, 5)
        self.addCleanup(process.terminate)
        self.binary_process = process
        line = process.stdout.readline().strip()
        self.assertTrue(line.startswith("listening on http://127.0.0.1:"), line)
        device_line = process.stdout.readline().strip()
        self.assertTrue(device_line.startswith("device listening on http://127.0.0.1:"), device_line)
        return int(line.rsplit(":", 1)[1]), int(device_line.rsplit(":", 1)[1])

    def login(self):
        response, _, headers = self.call("POST", "/v1/admin/session", {}, self.admin)
        self.assertEqual(response, 200)
        self.web = {"Cookie": headers["set-cookie"].split(";")[0]}

    def call(self, method, path, body=None, headers=None, *, port=None):
        if port is None:
            device_path = path.split("?", 1)[0].split("/")[2:3]
            port = self.device_port if device_path in (["devices"], ["device-commands"], ["device-results"]) else self.port
        connection = HTTPConnection("127.0.0.1", port, timeout=4)
        try:
            headers = dict(headers or {})
            if body is not None:
                headers["Content-Type"] = "application/json"
            connection.request(method, path, json.dumps(body) if body is not None else None, headers)
            result = connection.getresponse()
            data = result.read()
            if data and result.headers.get_content_type() == "application/json":
                data = json.loads(data)
            return result.status, data or None, {k.lower(): v for k, v in result.getheaders()}
        finally:
            connection.close()

    def request(self, method, path, body=None, headers=None, expected=200):
        actual, data, _ = self.call(method, path, body, headers)
        self.assertEqual(expected, actual, data)
        return data

    def register(self, client):
        key = self.request("POST", "/v1/admin/clients", {"client_id": client},
                           self.web, 201)["registration_key"]
        return {"Authorization": "Bearer " + key, "X-Client-Id": client}

    def approve(self, headers, capabilities=None):
        client = headers["X-Client-Id"]
        registration = {"client_id": client, "instance_id": client+"-instance"}
        if capabilities is not None:
            registration["capabilities"] = capabilities
        pending = self.request("POST", "/v1/devices/session", registration, headers, 202)
        self.assertEqual(pending["status"], "pending_approval")
        self.request("POST", "/v1/device-results", {}, headers, 403)
        self.request("POST", "/v1/device-commands/claim", {"ready": True}, headers, 403)
        self.request("POST", f"/v1/admin/clients/{client}/approve", {}, self.web)
        result = self.request("POST", "/v1/devices/session", registration, headers)
        headers.update({"X-Device-Session": result["device_session_id"],
                        "X-Lease-Epoch": str(result["lease_epoch"])})

    @staticmethod
    def rows(messages, direction="incoming"):
        return [{"sender": "测试成员", "text": text, "direction": direction} for text in messages]

    def capture(self, headers, capture_id, rows, group=None, **extra):
        page = {"id": capture_id, "name": "同名群", "title": "同名群(3)", "rows": rows, **extra}
        if group:
            page["group_id"] = group
        result = self.request("POST", "/v1/device-results",
                              {"captures": [page], "routes": {"同名群": "同名群(3)"}}, headers)
        self.assertEqual(result["ack"], [capture_id])
        return result["bindings"]["同名群"]

    def test_web_and_business_share_a_port_separate_from_device_api(self):
        self.assertNotEqual(self.port, self.device_port)
        status, html, _ = self.call("GET", "/", port=self.port)
        self.assertEqual(status, 200)
        self.assertIn("群消息网关".encode(), html)
        self.assertEqual(self.call("GET", "/", port=self.device_port)[0], 404)
        self.assertEqual(self.call("POST", "/v1/admin/session", {}, self.admin, port=self.device_port)[0], 404)

        device = self.register("split-port")
        registration = {"client_id": "split-port", "instance_id": "split-port-instance"}
        self.assertEqual(self.call("POST", "/v1/devices/session", registration, device, port=self.port)[0], 404)
        self.approve(device)
        self.assertEqual(self.call("GET", "/v1/devices/config", headers=device, port=self.port)[0], 404)
        self.assertEqual(self.call("POST", "/v1/device-commands/claim", {"ready": True}, device, port=self.port)[0], 404)
        self.assertEqual(self.call("POST", "/v1/device-results", {}, device, port=self.port)[0], 404)

        group = self.capture(device, "split-capture", self.rows(["设备端口采集"]))
        key = self.request("POST", "/v1/admin/api-keys", {
            "label": "双端口测试",
            "grants": [{"client_id": "split-port", "group_ids": [group], "scopes": ["messages:read"]}],
        }, self.web, 201)["api_key"]
        third = {"Authorization": "Bearer " + key}
        path = f"/v1/clients/split-port/groups/{group}/messages/recent"
        self.assertEqual(self.request("GET", path, headers=third)["messages"][0]["text"], "设备端口采集")
        self.assertEqual(self.call("GET", path, headers=third, port=self.device_port)[0], 404)

    def test_public_web_url_supports_nested_prefix(self):
        work = TemporaryDirectory()
        self.addCleanup(work.cleanup)
        config = Path(work.name) / "gateway.json"
        prefix = "/infra/prod/wx_msg_gateway"
        origin = "https://gateway.example.test:9443"
        config.write_text(json.dumps({"admin_token": "test-admin-key",
                                      "public_web_url": origin + prefix + "/"}))
        self.port, self.device_port = self.start_binary(config)
        proxy_host = {"Host": "gateway.example.test:9443"}
        browser = {**proxy_host, "Origin": origin}

        # The reverse proxy removes the prefix, preserving Host and Origin.
        status, html, _ = self.call("GET", "/", headers=proxy_host)
        self.assertEqual(status, 200)
        self.assertIn("群消息网关".encode(), html)
        self.request("GET", "/", expected=403)
        for headers in (proxy_host, {**browser, "Origin": "https://other.example.test"},
                        {**browser, "Host": "other.example.test"}):
            self.request("POST", "/v1/admin/session", {}, {**headers, **self.admin}, 401)

        status, _, headers = self.call("POST", "/v1/admin/session", {}, {**browser, **self.admin})
        self.assertEqual(status, 200)
        cookie = headers["set-cookie"].split("; ")
        self.assertIn("Path=" + prefix + "/v1", cookie)
        self.assertIn("Secure", cookie)
        self.web = {**browser, "Cookie": cookie[0]}

        device = self.register("prefixed-web")
        self.approve(device)
        group = self.capture(device, "prefixed-capture", self.rows(["隔离测试文字"]))
        groups_path = "/v1/clients/prefixed-web/groups"
        messages_path = groups_path + f"/{group}/messages/recent"
        web_get = {**proxy_host, "Cookie": cookie[0]}
        self.assertEqual(self.request("GET", messages_path, headers=web_get)
                         ["messages"][0]["text"], "隔离测试文字")
        self.request("GET", messages_path,
                     headers={**self.web, "Origin": "https://other.example.test"}, expected=401)
        self.request("POST", "/v1/admin/clients", {"client_id": "untrusted"},
                     web_get, expected=401)
        key = self.request("POST", "/v1/admin/api-keys", {
            "label": "前缀路由验收",
            "grants": [{"client_id": "prefixed-web", "group_ids": [group], "scopes": ["groups:read"]}],
        }, self.web, 201)["api_key"]
        third = {**proxy_host, "Authorization": "Bearer " + key}
        self.assertEqual(self.request("GET", groups_path, headers=third)
                         ["groups"][0]["group_id"], group)
        self.request("GET", messages_path, headers=third, expected=403)
        self.assertEqual(self.call("GET", groups_path, headers=third, port=self.device_port)[0], 404)

    def test_web_requests_scan_resume_until_device_acknowledges(self):
        client = "paused-phone"
        device = self.register(client)
        self.approve(device)
        control = f"/v1/admin/clients/{client}/resume-scan"
        heartbeat = "/v1/devices/heartbeat"
        self.request("POST", control, {}, self.web, 409)
        self.request("POST", heartbeat, {"status": "原群待核对", "scan_paused": True}, device)
        self.request("POST", control, {}, expected=401)
        self.assertTrue(self.request("POST", control, {}, self.web, 202)["resume_pending"])
        self.assertTrue(self.request("GET", "/v1/admin/clients", headers=self.web)["clients"][0]["resume_pending"])
        first = self.request("POST", heartbeat, {"scan_paused": True}, device)["resume_scan_id"]
        self.request("POST", control, {}, self.web, 202)
        self.assertEqual(self.request("POST", heartbeat, {"scan_paused": True}, device)["resume_scan_id"], first)
        self.assertNotIn("resume_scan_id", self.request("POST", heartbeat, {
            "scan_paused": True, "resume_ack": first, "status": "原群仍有草稿",
        }, device))
        device_state = self.request("GET", "/v1/admin/clients", headers=self.web)["clients"][0]
        self.assertFalse(device_state["resume_pending"])
        self.assertEqual(device_state["scan_paused"], 1)
        self.assertEqual(device_state["status"], "原群仍有草稿")
        self.request("POST", control, {}, self.web, 202)
        second = self.request("POST", heartbeat, {"scan_paused": True}, device)["resume_scan_id"]
        self.assertNotEqual(first, second)
        self.assertNotIn("resume_scan_id", self.request("POST", heartbeat, {
            "scan_paused": False, "resume_ack": second,
        }, device))
        self.request("POST", control, {}, self.web, 409)

    def test_web_session_survives_gateway_restart_until_expired(self):
        original_cookie = self.web
        self.binary_process.terminate()
        self.binary_process.wait(5)
        self.port, self.device_port = self.start_binary(self.config)
        self.request("GET", "/v1/admin/clients", headers=original_cookie)

        config = {"admin_token": "test-admin-key", "web_session_days": 30}
        self.config.write_text(json.dumps(config))
        self.port, self.device_port = self.start_binary(self.config)
        status, _, headers = self.call("POST", "/v1/admin/session", {}, self.admin)
        self.assertEqual(status, 200)
        self.assertIn("Max-Age=2592000", headers["set-cookie"])
        long_cookie = {"Cookie": headers["set-cookie"].split(";")[0]}
        with sqlite3.connect(self.config.with_name("group-gateway-v1.sqlite3")) as db:
            db.execute("UPDATE web_sessions SET expires_at=0 WHERE expires_at < "
                       "(SELECT MAX(expires_at) FROM web_sessions)")
        self.request("GET", "/v1/admin/clients", headers=original_cookie, expected=401)
        self.request("GET", "/v1/admin/clients", headers=long_cookie)

        config["admin_token"] = "rotated-admin-key"
        self.config.write_text(json.dumps(config))
        self.port, self.device_port = self.start_binary(self.config)
        self.request("GET", "/v1/admin/clients", headers=long_cookie, expected=401)

    def test_delete_revoked_key_preserves_send_history(self):
        device = self.register("delete-key")
        self.approve(device)
        group = self.capture(device, "delete-key-capture", self.rows(["已有消息"]))
        keys = "/v1/admin/api-keys"
        issued = self.request("POST", keys, {
            "label": "可删除的测试 Key",
            "grants": [{"client_id": "delete-key", "group_ids": [group], "scopes": ["messages:send"]}],
        }, self.web, 201)
        key_path = keys + "/" + issued["key_id"]
        third = {"Authorization": "Bearer " + issued["api_key"], "Idempotency-Key": "delete-key-task"}
        sends = f"/v1/clients/delete-key/groups/{group}/send-requests"
        task = self.request("POST", sends, {"text": "已有发送任务"}, third, 202)
        self.request("DELETE", key_path+"/record", headers=third, expected=403)
        self.request("DELETE", key_path+"/record", headers=self.web, expected=409)
        self.request("DELETE", key_path, headers=self.web, expected=204)
        self.assertEqual(self.request("GET", keys, headers=self.web)["keys"][0]["enabled"], 0)
        self.request("DELETE", key_path+"/record", headers=self.web, expected=204)
        self.assertEqual(self.request("GET", keys, headers=self.web)["keys"], [])
        self.request("DELETE", key_path+"/record", headers=self.web, expected=404)
        self.request("POST", key_path+"/rotate", {}, self.web, 404)
        self.request("GET", sends+"/"+task["task_id"], headers=third, expected=401)
        self.assertEqual(self.request("GET", sends+"/"+task["task_id"], headers=self.web)["task_id"], task["task_id"])

    def test_api_key_reveal_is_explicit_and_persistent(self):
        self.register("show-key")
        issued = self.request("POST", "/v1/admin/api-keys", {
            "label": "可查看的测试 Key",
            "grants": [{"client_id": "show-key", "group_ids": "*", "scopes": ["groups:read"]}],
        }, self.web, 201)
        path = "/v1/admin/api-keys/" + issued["key_id"]
        third = {"Authorization": "Bearer " + issued["api_key"]}
        self.request("GET", path+"/secret", headers=third, expected=403)
        listed = self.request("GET", "/v1/admin/api-keys", headers=self.web)["keys"]
        self.assertEqual(listed[0]["revealable"], 1)
        self.assertNotIn(issued["api_key"], json.dumps(listed))
        self.assertEqual(self.request("GET", path+"/secret", headers=self.web)["api_key"], issued["api_key"])

        self.port, self.device_port = self.start_binary(self.config)
        self.login()
        self.assertEqual(self.request("GET", path+"/secret", headers=self.web)["api_key"], issued["api_key"])
        rotated = self.request("POST", path+"/rotate", {}, self.web)["api_key"]
        self.assertNotEqual(rotated, issued["api_key"])
        self.assertEqual(self.request("GET", path+"/secret", headers=self.web)["api_key"], rotated)
        self.request("DELETE", path, headers=self.web, expected=204)
        self.request("GET", path+"/secret", headers=self.web, expected=404)

    def test_two_approved_clients_read_and_send_over_http(self):
        self.assertEqual(self.request("GET", "/v1/admin/clients", headers=self.web)["clients"], [])
        a, b = self.register("phone-a"), self.register("phone-b")
        self.request("POST", "/v1/admin/clients/phone-a/approve", {}, self.web, 409)
        self.request("POST", "/v1/devices/session", {"client_id": "phone-b", "instance_id": "imposter"},
                     {**a, "X-Client-Id": "phone-b"}, 401)
        self.approve(a)
        self.approve(b)
        self.request("POST", "/v1/devices/session", {"client_id": "phone-a", "instance_id": "second"}, a, 409)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            a_capture = pool.submit(self.capture, a, "same-batch", self.rows([f"A-{n}" for n in range(1, 36)]))
            b_capture = pool.submit(self.capture, b, "same-batch", self.rows(["B-1"]))
            group_a, group_b = a_capture.result(), b_capture.result()
        self.assertNotEqual(group_a, group_b)
        path_a = f"/v1/clients/phone-a/groups/{group_a}"
        path_b = f"/v1/clients/phone-b/groups/{group_b}"
        grants = [{"client_id": c, "group_ids": "*",
                   "scopes": ["groups:read", "messages:read", "messages:send"]} for c in ("phone-a","phone-b")]
        key = self.request("POST", "/v1/admin/api-keys", {"label": "测试", "grants": grants}, self.web, 201)["api_key"]
        third = {"Authorization": "Bearer "+key, "Idempotency-Key": "same-key"}
        recent = self.request("GET", path_a+"/messages/recent", headers=third)["messages"]
        self.assertEqual([m["offset"] for m in recent], list(range(6,36)))
        self.assertTrue(all(m["client_id"] == "phone-a" and m["group_id"] == group_a for m in recent))
        self.assertEqual(self.request("GET", path_b+"/messages?after_offset=0&limit=30", headers=third)
                         ["messages"][0]["offset"], 1)
        self.request("GET", f"/v1/clients/phone-b/groups/{group_a}/messages/recent", headers=third, expected=404)
        self.capture(a, "same-batch", self.rows([f"A-{n}" for n in range(1,36)]), None)
        a_task = self.request("POST", path_a+"/send-requests", {"text":"A 回复"}, third, 202)
        self.assertEqual(self.request("POST", path_a+"/send-requests", {"text":"A 回复"}, third)["task_id"],
                         a_task["task_id"])
        self.request("POST", path_a+"/send-requests", {"text":"不同文字"}, third, 409)
        self.request("POST", "/v1/device-commands/claim", {"ready": True}, b, 204)
        alert = {"sn":"001","problem":"丢包","started_at":"2026-11-07T14:28:00+08:00","suggestion":"检查链路"}
        b_task = self.request("POST", path_b+"/alert-send-requests", alert, third, 202)

        def send(headers, group, task):
            command = self.request("POST", "/v1/device-commands/claim", {"ready":True}, headers)
            self.assertEqual((command["client_id"], command["group_id"], command["task_id"]),
                             (headers["X-Client-Id"], group, task["task_id"]))
            endpoint = f"/v1/device-commands/{task['task_id']}/click-reservation"
            permit = {"group_id":group,"title":command["title"],"body_digest":command["body_digest"]}
            self.assertTrue(self.request("POST", endpoint, permit, headers)["may_click"])
            self.assertFalse(self.request("POST", endpoint, permit, headers)["may_click"])
            self.request("POST", "/v1/device-results", {"completion": {
                "id":task["task_id"], "event_id":"same-event", "group_id":group, "state":"confirmed"
            }}, headers)
            return command["text"]

        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            a_send = pool.submit(send, a, group_a, a_task)
            b_send = pool.submit(send, b, group_b, b_task)
            self.assertEqual(a_send.result(), "A 回复")
            self.assertIn("SN：001", b_send.result())
        self.assertEqual(self.request("GET", path_a+"/send-requests/"+a_task["task_id"], headers=self.web)["status"],
                         "confirmed")
        self.assertEqual(self.request("GET", path_b+"/send-requests/"+b_task["task_id"], headers=third)["status"],
                         "confirmed")
        self.request("GET", path_b+"/send-requests/"+a_task["task_id"], headers=third, expected=404)
        self.request("DELETE", "/v1/admin/clients/phone-a/key", headers=self.web, expected=204)
        self.request("POST", "/v1/devices/heartbeat", {}, a, 401)
        self.request("POST", "/v1/devices/heartbeat", {}, b)

    def test_history_windows_persist_and_keep_group_offsets_on_gap_and_retry(self):
        a = self.register("phone-history")
        self.approve(a)
        group = self.capture(a, "start", self.rows(["3", "4"]), sweep_id="start")
        path = f"/v1/clients/phone-history/groups/{group}"
        self.assertEqual(self.request("GET", path+"/messages/recent", headers=self.web)["messages"], [])
        self.capture(a, "older", self.rows(["1", "2", "3"]), group, sweep_id="start",
                     older_than="start", history_status="complete")
        self.capture(a, "older", self.rows(["1", "2", "3"]), group, sweep_id="start",
                     older_than="start", history_status="complete")
        first = self.request("GET", path+"/messages?after_offset=0&limit=2", headers=self.web)["messages"]
        self.assertEqual([(row["offset"], row["text"]) for row in first], [(1, "1"), (2, "2")])
        self.assertEqual(self.request("GET", path, headers=self.web)["history_status"], "complete")
        self.capture(a, "unlinked", self.rows(["7", "8"]), group, sweep_id="unlinked", history_status="gap")
        self.assertEqual(self.request("GET", path, headers=self.web)["history_status"], "gap")
        pending = self.request("GET", path+"/messages?after_offset=2&limit=10", headers=self.web)["messages"]
        self.assertEqual([(row["offset"], row["text"]) for row in pending], [(3, "3"), (4, "4")])
        self.capture(a, "recovered", self.rows(["5", "6", "7", "8"]), group, sweep_id="recovered")
        self.capture(a, "recovered-older", self.rows(["1", "2", "3", "4", "5"]), group,
                     sweep_id="recovered", older_than="recovered", history_status="complete")
        final = self.request("GET", path+"/messages?after_offset=2&limit=10", headers=self.web)["messages"]
        self.assertEqual([(row["offset"], row["text"]) for row in final], [(i, str(i)) for i in range(3, 9)])
        self.assertEqual(self.request("GET", path, headers=self.web)["history_status"], "complete")

    def test_newest_window_advances_despite_history_gap(self):
        device = self.register("partial-history")
        self.approve(device)
        group = self.capture(device, "known", self.rows([str(i) for i in range(1, 19)]),
                             history_status="complete")
        path = f"/v1/clients/partial-history/groups/{group}"
        newest = self.rows([str(i) for i in range(12, 19)] + ["新入站消息"])
        older = self.rows(["旧窗口中的未对齐文字", "12"])
        for attempt in ("first", "repeat"):
            self.capture(device, attempt, newest, group, sweep_id=attempt)
            self.capture(device, attempt + "-older", older, group, sweep_id=attempt,
                         older_than=attempt, history_status="gap")
            rows = self.request("GET", path+"/messages?after_offset=18&limit=10", headers=self.web)["messages"]
            self.assertEqual([(row["offset"], row["text"]) for row in rows], [(19, "新入站消息")])
            self.assertEqual(self.request("GET", path, headers=self.web)["history_status"], "gap")

    def test_group_key_permissions_and_web_task_history(self):
        device = self.register("scope-test")
        self.approve(device)
        group = self.capture(device, "scope-start", self.rows(["消息"]))
        path = f"/v1/clients/scope-test/groups/{group}"
        api_key = self.request("POST", "/v1/admin/api-keys", {
            "label": "只查群", "grants": [{"client_id": "scope-test", "group_ids": [group], "scopes": ["groups:read"]}]
        }, self.web, 201)
        third = {"Authorization": "Bearer " + api_key["api_key"]}
        self.request("GET", path, headers=third)
        self.request("GET", path+"/messages/recent", headers=third, expected=403)
        self.request("GET", "/v1/admin/clients", headers=third, expected=403)
        task = self.request("POST", path+"/send-requests", {"text": "Web 提交"},
                            {**self.web, "Idempotency-Key": "web-key"}, 202)
        history = self.request("GET", path+"/send-requests", headers=self.web)["tasks"]
        self.assertEqual(history[0]["task_id"], task["task_id"])

    def test_group_count_change_preserves_binding_only_with_history_anchor(self):
        device = self.register("roster-change")
        self.approve(device, ["SEND_NATIVE_MENTION"])
        history = ["确认群聊第一条", "不同的第二条", "不同的第三条"]
        group = self.capture(device, "before", self.rows(history))
        path = f"/v1/clients/roster-change/groups/{group}"
        snapshot = {"id": "before-members", "group_id": group, "title": "同名群(3)", "names": ["甲", "乙"]}
        self.request("POST", "/v1/device-results", {"member_snapshot": snapshot}, device)
        previous = self.request("GET", path+"/messages/recent", headers=self.web)["messages"]

        def changed(capture_id, binding, messages):
            result = self.request("POST", "/v1/device-results", {
                "captures": [{"id": capture_id, "name": "同名群", "title": "同名群(4)",
                              "group_id": binding, "rows": self.rows(messages)}],
                "routes": {"同名群": "同名群(4)"}
            }, device)
            self.assertEqual(result["bindings"]["同名群"], group)

        changed("no-anchor", group, ["另一个同名群的内容"])
        self.assertEqual(self.request("GET", path, headers=self.web)["title"], "同名群(3)")
        changed("no-binding", None, history)
        self.assertEqual(self.request("GET", path, headers=self.web)["title"], "同名群(3)")
        changed("count-changed", group, history + ["成员变化后的新消息"])
        updated = self.request("GET", path, headers=self.web)
        self.assertEqual((updated["title"], updated["group_id"], updated["routable"]), ("同名群(4)", group, True))
        recent = self.request("GET", path+"/messages/recent", headers=self.web)["messages"]
        self.assertEqual(recent[:3], previous)
        self.assertEqual(recent[-1]["offset"], 4)
        self.assertEqual(self.request("GET", path+"/members", headers=self.web)["status"], "pending_sync")
        self.request("POST", "/v1/device-results", {"member_snapshot": {
            **snapshot, "id": "new-members", "title": "同名群(4)", "names": ["甲", "乙", "丙"],
        }}, device)
        self.assertEqual(len(self.request("GET", path+"/members", headers=self.web)["members"]), 3)

    def test_reply_mentions_original_sender_without_member_snapshot(self):
        a, b = self.register("reply-a"), self.register("reply-b")
        self.approve(a, ["SEND_NATIVE_MENTION", "MENTION_MESSAGE_SENDER"])
        self.approve(b, ["SEND_NATIVE_MENTION"])
        source_rows = [
            {"sender": "发件人", "text": "请看一下", "direction": "incoming"},
            {"sender": None, "text": "姓名不可见", "direction": "incoming"},
            {"sender": "本机账号", "text": "自己的话", "direction": "outgoing"},
        ]
        ga = self.capture(a, "original-a", source_rows)
        gb = self.capture(b, "original-b", source_rows)
        path = f"/v1/clients/reply-a/groups/{ga}"
        other = f"/v1/clients/reply-b/groups/{gb}"
        self.assertTrue(self.request("GET", path, headers=self.web)["mention_sender_supported"])
        self.assertFalse(self.request("GET", other, headers=self.web)["mention_sender_supported"])
        self.assertEqual(self.request("GET", path+"/members", headers=self.web)["status"], "pending_sync")
        originals = self.request("GET", path+"/messages/recent", headers=self.web)["messages"]
        other_rows = self.request("GET", other+"/messages/recent", headers=self.web)["messages"]
        origin = originals[0]["message_id"]
        body = {"text": "收到", "mention_sender_of": origin}
        key = {**self.web, "Idempotency-Key": "reply-1"}
        self.request("POST", other+"/send-requests", body, key, 404)
        self.request("POST", other+"/send-requests", {**body, "mention_sender_of": other_rows[0]["message_id"]}, key, 409)
        self.request("POST", path+"/send-requests", {**body, "mentions": []}, key, 400)
        for unavailable in originals[1:]:
            self.request("POST", path+"/send-requests", {**body, "mention_sender_of": unavailable["message_id"]}, key, 409)
        task = self.request("POST", path+"/send-requests", body, key, 202)
        self.assertEqual(self.request("POST", path+"/send-requests", body, key)["task_id"], task["task_id"])
        self.request("POST", "/v1/device-commands/claim", {"ready": True}, b, 204)
        command = self.request("POST", "/v1/device-commands/claim", {"ready": True}, a)
        self.assertEqual(command["task_id"], task["task_id"])
        self.assertEqual(command["type"], "SEND_NATIVE_MENTION")
        self.assertEqual(command["mention"], {"source_message_id": origin, "display_name": "发件人"})
        proof = {field: command[field] for field in ("group_id", "title", "body_digest")}
        reservation = f"/v1/device-commands/{task['task_id']}/click-reservation"
        self.request("POST", reservation, {**proof, "source_message_id": other_rows[0]["message_id"]}, a, 409)
        self.assertTrue(self.request("POST", reservation, {**proof, "source_message_id": origin}, a)["may_click"])
        self.assertFalse(self.request("POST", reservation, {**proof, "source_message_id": origin}, a)["may_click"])

    def test_confirmed_mention_links_only_matching_outgoing_message(self):
        device = self.register("receipt-mention")
        self.approve(device, ["SEND_NATIVE_MENTION", "MENTION_MESSAGE_SENDER"])
        history = self.rows(["原消息"])
        group = self.capture(device, "history", history)
        path = f"/v1/clients/receipt-mention/groups/{group}"
        source = self.request("GET", path+"/messages/recent", headers=self.web)["messages"][0]
        headers = {**self.web, "Idempotency-Key": "reply-mention"}
        task = self.request("POST", path+"/send-requests",
                            {"text": "测试正文", "mention_sender_of": source["message_id"]}, headers, 202)
        command = self.request("POST", "/v1/device-commands/claim", {"ready": True}, device)
        proof = {field: command[field] for field in ("group_id", "title", "body_digest")}
        proof["source_message_id"] = source["message_id"]
        task_path = path+"/send-requests/"+task["task_id"]
        reservation = f"/v1/device-commands/{task['task_id']}/click-reservation"
        self.assertTrue(self.request("POST", reservation, proof, device)["may_click"])
        self.request("POST", "/v1/device-results", {"completion": {
            "id": task["task_id"], "event_id": "mention-confirmed", "group_id": group, "state": "confirmed"
        }}, device)
        wrong = history + self.rows(["测试正文", "测试正文   @所有人"], "outgoing")
        self.capture(device, "wrong", wrong, group)
        self.assertIsNone(self.request("GET", task_path, headers=self.web)["message_id"])
        right = wrong + self.rows(["测试正文   @测试成员\u00a0"], "outgoing")
        self.capture(device, "right", right, group)
        sent = self.request("GET", path+"/messages/recent", headers=self.web)["messages"][-1]
        receipt = self.request("GET", task_path, headers=self.web)
        self.assertEqual(receipt["status"], "confirmed")
        self.assertEqual(receipt["message_id"], sent["message_id"])
        self.assertEqual(receipt["offset"], sent["offset"])

    def test_native_mentions_are_scoped_and_raw_text_does_not_mark_self(self):
        a, b = self.register("mention-a"), self.register("mention-b")
        for device in (a, b):
            self.approve(device, ["READ_GROUP_WINDOW", "SEND_TEXT", "SEND_NATIVE_MENTION"])
        incoming = self.rows(["手写 @本机账号", "明确提及", "提及所有人", "无提及"])
        for row, state in zip(incoming[1:], ["direct", "all", "none"]):
            row["mention_self"] = state
        group_a = self.capture(a, "a-window", incoming)
        group_b = self.capture(b, "b-window", self.rows(["另一台手机"]))
        path_a = f"/v1/clients/mention-a/groups/{group_a}"
        path_b = f"/v1/clients/mention-b/groups/{group_b}"
        recent = self.request("GET", path_a+"/messages/recent", headers=self.web)["messages"]
        self.assertEqual([m["mention_self"] for m in recent], ["unknown", "direct", "all", "none"])
        self.capture(a, "a-repeat", self.rows([m["text"] for m in recent]), group_a)
        self.assertEqual(self.request("GET", path_a+"/messages?after_offset=0&limit=30", headers=self.web)["messages"], recent)
        self.assertEqual(self.request("GET", path_a+"/members", headers=self.web)["status"], "pending_sync")

        for index, (device, group) in enumerate(((a, group_a), (b, group_b))):
            data = {"id": "members-"+str(index), "group_id": group, "title": "同名群(3)",
                    "names": ["成员甲", "成员乙"]}
            self.request("POST", "/v1/device-results", {"member_snapshot": data}, device)
            other = b if device is a else a
            self.request("POST", "/v1/device-results", {"member_snapshot": data}, other, 404)
        api_key = self.request("POST", "/v1/admin/api-keys", {"label": "提及测试", "grants": [
            {"client_id": "mention-a", "group_ids": [group_a], "scopes": ["members:read", "messages:send"]},
        ]}, self.web, 201)
        third = {"Authorization": "Bearer " + api_key["api_key"], "Idempotency-Key": "mention"}
        members = self.request("GET", path_a+"/members", headers=third)["members"]
        member = next(row for row in members if row["display_name"] == "成员甲")
        self.request("GET", path_b+"/members", headers=third, expected=403)
        body = {"text": "请处理", "mentions": [{"member_id": member["member_id"]}]}
        task = self.request("POST", path_a+"/send-requests", body, third, 202)
        self.assertEqual(self.request("POST", path_a+"/send-requests", body, third)["task_id"], task["task_id"])
        changed = {"text": body["text"], "mentions": [{"member_id": next(row["member_id"] for row in members if row != member)}]}
        self.request("POST", path_a+"/send-requests", changed, third, 409)
        self.request("POST", path_b+"/send-requests", body, {**self.web, "Idempotency-Key": "other"}, 409)
        self.request("POST", "/v1/device-commands/claim", {"ready": True}, b, 204)
        command = self.request("POST", "/v1/device-commands/claim", {"ready": True}, a)
        self.assertEqual(command["type"], "SEND_NATIVE_MENTION")
        self.assertEqual(command["mention"], {**member, "roster": ["成员乙", "成员甲"]})
        reservation = "/v1/device-commands/"+command["task_id"]+"/click-reservation"
        proof = {key: command[key] for key in ("title", "group_id", "body_digest")}
        self.request("POST", reservation, proof, a, 409)
        proof["member_id"] = member["member_id"]
        self.assertTrue(self.request("POST", reservation, proof, a)["may_click"])
        self.assertFalse(self.request("POST", reservation, proof, a)["may_click"])
        self.request("POST", "/v1/device-results", {"completion": {
            "id": task["task_id"], "event_id": "uncertain", "group_id": group_a, "state": "uncertain"
        }}, a)
        self.capture(a, "outgoing-plain", incoming + self.rows(["请处理"], "outgoing"), group_a)
        self.assertEqual(self.request("GET", path_a+"/send-requests/"+task["task_id"],
                                      headers=third)["status"], "uncertain")
        alert = {"sn": "TEST", "problem": "测试告警", "started_at": "2026-11-07T10:00:00+08:00",
                 "suggestion": "测试", "mentions": body["mentions"]}
        task_alert = self.request("POST", path_a+"/alert-send-requests", alert,
                                  {**third, "Idempotency-Key": "alert"}, 202)
        self.request("POST", "/v1/device-results", {"member_snapshot": {
            "id": "duplicates", "group_id": group_a, "title": "同名群(3)", "names": ["成员甲", "成员甲"]
        }}, a)
        self.assertEqual(self.request("GET", path_a+"/members", headers=third)["members"], [])
        self.request("POST", "/v1/device-commands/claim", {"ready": True}, a, 204)
        self.assertEqual(self.request("GET", path_a+"/send-requests/"+task_alert["task_id"],
                                      headers=third)["status"], "blocked")

    def test_migrates_existing_gateway_database(self):
        device = self.register("existing-client")
        self.approve(device)
        messages = self.rows(["已有群消息"])
        group = self.capture(device, "existing-batch", messages)
        path = f"/v1/clients/existing-client/groups/{group}"
        issued = self.request("POST", "/v1/admin/api-keys", {
            "label": "已有接口 Key",
            "grants": [{"client_id": "existing-client", "group_ids": [group],
                        "scopes": ["messages:read", "messages:send"]}],
        }, self.web, 201)
        key = issued["api_key"]
        third = {"Authorization": "Bearer " + key, "Idempotency-Key": "previous-request"}
        previous_messages = self.request("GET", path+"/messages/recent", headers=third)["messages"]
        task = self.request("POST", path+"/send-requests", {"text": "待发送任务"}, third, 202)
        self.binary_process.terminate()
        self.binary_process.wait(5)
        # Match the stored data and schema before Rust added these fields.
        with sqlite3.connect(self.config.with_name("group-gateway-v1.sqlite3")) as db:
            db.executescript("""
                UPDATE api_keys SET key_ciphertext=NULL;
                ALTER TABLE clients DROP COLUMN native_mentions;
                ALTER TABLE clients DROP COLUMN mention_sender;
                ALTER TABLE clients DROP COLUMN scan_paused;
                ALTER TABLE clients DROP COLUMN resume_id;
                ALTER TABLE clients DROP COLUMN resume_until;
                ALTER TABLE messages DROP COLUMN mention_self;
                ALTER TABLE tasks DROP COLUMN sent_text;
                DROP TABLE web_sessions;
                DROP TABLE member_snapshots;
            """)
        for row in previous_messages:
            self.assertEqual(row.pop("mention_self"), "unknown")
        self.port, self.device_port = self.start_binary(self.config)
        self.login()
        secret_path = f"/v1/admin/api-keys/{issued['key_id']}/secret"
        self.request("GET", secret_path, headers=self.web, expected=409)
        self.request("POST", secret_path, {"api_key": "wrong"}, self.web, 409)
        self.request("POST", secret_path, {"api_key": key}, self.web, 204)
        self.assertEqual(self.request("GET", secret_path, headers=self.web)["api_key"], key)
        self.assertEqual(self.capture(device, "existing-batch", messages), group)
        migrated = self.request("GET", path+"/messages/recent", headers=third)["messages"]
        self.assertEqual([row.pop("mention_self") for row in migrated], ["unknown"] * len(previous_messages))
        self.assertEqual(migrated, previous_messages)
        self.assertEqual(self.request("POST", path+"/send-requests", {"text": "待发送任务"}, third), task)
        self.assertEqual(self.request("POST", "/v1/device-commands/claim", {"ready": True}, device)["task_id"],
                         task["task_id"])
