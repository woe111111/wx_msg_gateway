import unittest

from server import Store


class DiagnosticsTest(unittest.TestCase):
    def test_phone_event_ack_and_retry(self):
        store = Store(":memory:")
        self.addCleanup(store.db.close)
        packet = {"status": "微信页面未识别", "events": [{"id": "test-event", "text": "列表 0，聊天区 1"}]}
        self.assertEqual(store.sync(packet)["event_ack"], ["test-event"])
        store.sync(packet)
        self.assertEqual([row["detail"] for row in store.view()["logs"]], ["列表 0，聊天区 1", "收到手机连接"])

    def test_legacy_heartbeat_has_diagnostic(self):
        store = Store(":memory:")
        self.addCleanup(store.db.close)
        store.sync({"status": "请打开微信的会话列表或群聊天页"})
        view = store.view()
        self.assertTrue(view["online"])
        self.assertEqual(view["logs"][0]["detail"], view["phone"])


class SharedGatewayTest(unittest.TestCase):
    def setUp(self):
        self.store = Store(":memory:")
        self.addCleanup(self.store.db.close)

    @staticmethod
    def page(capture_id, *texts):
        return {"id": capture_id, "name": "测试群", "title": "测试群(3)",
                "rows": [{"sender": "测试成员", "text": text} for text in texts]}

    def test_same_name_groups_have_separate_messages_routes_and_jobs(self):
        routes = {"测试群": "测试群(3)"}
        self.store.sync({"captures": [self.page("same-capture-id", "微信文字")], "routes": routes})
        self.store.sync({"platform": "wecom", "captures": [self.page("same-capture-id", "企微文字")],
                         "routes": routes})
        groups = {group["platform"]: group for group in self.store.view()["groups"]}
        weixin, wecom = groups["weixin"]["id"], groups["wecom"]["id"]
        self.assertNotEqual(weixin, wecom)
        self.assertEqual(self.store.view(weixin)["messages"][0]["text"], "微信文字")
        self.assertEqual(self.store.view(wecom)["messages"][0]["text"], "企微文字")
        wecom_task = self.store.enqueue(wecom, "企微回复", "wecom-send")
        self.assertEqual(self.store.enqueue(wecom, "企微回复", "wecom-send")["id"], wecom_task["id"])
        self.assertIsNone(self.store.sync({"ready": True})["job"])
        weixin_task = self.store.enqueue(weixin, "微信回复", "weixin-send")
        self.assertEqual(self.store.sync({"ready": True})["job"]["id"], weixin_task["id"])
        self.assertEqual(self.store.sync({"platform": "wecom", "ready": True})["job"]["id"], wecom_task["id"])
        completion = {"id": wecom_task["id"], "state": "visible", "detail": "test receipt"}
        self.assertIsNone(self.store.sync({"completion": completion})["completion_ack"])
        self.assertEqual(self.store.view(wecom)["sends"][0]["state"], "claimed")
        self.assertEqual(self.store.sync({"platform": "wecom", "completion": completion})["completion_ack"],
                         wecom_task["id"])
        self.assertEqual(self.store.view(wecom)["sends"][0]["state"], "visible")
        self.store.sync({"platform": "wecom", "routes": {}})
        groups = {group["platform"]: group["routable"] for group in self.store.view()["groups"]}
        self.assertEqual(groups, {"weixin": 1, "wecom": 0})

    def test_retry_overlap_and_consecutive_equal_messages(self):
        def sync(page):
            return self.store.sync({"platform": "wecom", "captures": [page]})["ack"]

        self.assertEqual(sync(self.page("one", "a", "重复")), ["one"])
        self.assertEqual(sync(self.page("one", "a", "重复")), ["one"])
        sync(self.page("two", "a", "重复", "重复"))
        sync(self.page("three", "重复", "b"))
        sync(self.page("four", "a", "重复"))
        sync(self.page("five", "缺少重合"))
        group = self.store.view()["groups"][0]["id"]
        messages = self.store.view(group)["messages"]
        self.assertEqual([message["text"] for message in messages], ["a", "重复", "重复", "b", "缺少重合"])
        self.assertEqual([message["quality"] for message in messages],
                         ["baseline", "baseline", "ui_overlap", "ui_overlap", "unlinked"])
        self.assertEqual([message["text"] for message in self.store.view(group, messages[2]["seq"])["messages"]],
                         ["b", "缺少重合"])

    def test_migrates_old_database_and_preserves_ids_and_retry_receipts(self):
        import sqlite3
        from pathlib import Path
        from tempfile import TemporaryDirectory

        with TemporaryDirectory() as directory:
            path = Path(directory) / "legacy.sqlite3"
            db = sqlite3.connect(path)
            db.executescript("""
                CREATE TABLE groups(id TEXT PRIMARY KEY, name TEXT NOT NULL UNIQUE, title TEXT NOT NULL,
                    routable INTEGER NOT NULL DEFAULT 0, snapshot TEXT NOT NULL DEFAULT '[]');
                INSERT INTO groups VALUES('old-id','测试群','测试群(3)',1,'[{"sender":"测试成员","text":"已有"}]');
                CREATE TABLE captures(id TEXT PRIMARY KEY);
                INSERT INTO captures VALUES('old-capture');
                CREATE TABLE messages(seq INTEGER PRIMARY KEY AUTOINCREMENT, group_id TEXT NOT NULL,
                    sender TEXT, text TEXT NOT NULL, observed REAL NOT NULL, quality TEXT NOT NULL);
                INSERT INTO messages VALUES(7,'old-id','测试成员','已有',0,'baseline');
            """)
            db.close()
            store = Store(path)
            try:
                store.sync({"captures": [self.page("old-capture", "重复上报的捕获")],
                            "routes": {"测试群": "测试群(3)"}})
                store.sync({"platform": "wecom", "captures": [self.page("old-capture", "企微文字")]})
                groups = {group["platform"]: group for group in store.view()["groups"]}
                self.assertEqual(groups["weixin"]["id"], "old-id")
                self.assertTrue(groups["weixin"]["routable"])
                self.assertEqual([(row["seq"], row["text"]) for row in store.view("old-id")["messages"]], [(7, "已有")])
            finally:
                store.db.close()

    def test_late_outgoing_observation_updates_timeout_without_requeueing(self):
        def sync(**packet):
            return self.store.sync({"platform": "wecom", **packet})

        page = self.page("baseline", "已有正文")
        sync(captures=[page], routes={page["name"]: page["title"]})
        group = self.store.view()["groups"][0]["id"]
        task = self.store.enqueue(group, "reply-test？ \n", "late-send")
        self.assertEqual(sync(ready=True)["job"]["id"], task["id"])
        sync(completion={"id": task["id"], "state": "uncertain", "detail": "等待界面超时"})
        inbound = self.page("inbound", "已有正文", "reply-test？")
        sync(captures=[inbound])
        self.assertEqual(self.store.view(group)["sends"][0]["state"], "uncertain")
        outgoing = self.page("outgoing", "已有正文", "reply-test？", "reply-test？")
        outgoing["rows"][-1]["sender"] = "本机账号"
        sync(captures=[outgoing])
        self.assertEqual(self.store.view(group)["sends"][0]["state"], "observed")
        self.assertIsNone(sync(ready=True)["job"])
        sync(completion={"id": task["id"], "state": "uncertain", "detail": "等待界面超时"})
        self.assertEqual(self.store.view(group)["sends"][0]["state"], "observed")

    def test_late_observation_does_not_choose_between_equal_send_jobs(self):
        def sync(**packet):
            return self.store.sync({"platform": "wecom", **packet})

        page = self.page("baseline", "已有正文")
        sync(captures=[page], routes={page["name"]: page["title"]})
        group = self.store.view()["groups"][0]["id"]
        for key in ("first", "second"):
            job = self.store.enqueue(group, "reply-test", key)
            self.assertEqual(sync(ready=True)["job"]["id"], job["id"])
            sync(completion={"id": job["id"], "state": "uncertain", "detail": "等待界面超时"})
        outgoing = self.page("ambiguous", "已有正文", "reply-test")
        outgoing["rows"][-1]["sender"] = "本机账号"
        sync(captures=[outgoing])
        self.assertEqual([job["state"] for job in self.store.view(group)["sends"]], ["uncertain", "uncertain"])
