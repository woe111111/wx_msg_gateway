package local.mdnode.wecomprobe;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

final class WeComLayoutTest {
    public static void main(String[] args) {
        UiNode root = node("", "FrameLayout", "", 0, 0, 1080, 2280);
        root.children.add(node("", "TextView", "消息", 490, 140, 590, 190));
        root.children.add(node("", "TextView", "消息", 80, 2100, 135, 2120));
        UiNode list = node("db5", "ListView", "", 0, 234, 1080, 1981);
        root.children.add(list);
        UiNode toolbar = node("", "LinearLayout", "", 0, 234, 1080, 378);
        toolbar.children.add(node("", "TextView", "日程", 400, 290, 480, 340));
        list.children.add(toolbar);
        UiNode row = node("", "LinearLayout", "", 0, 378, 1080, 594);
        row.children.add(node("", "TextView", "测试群", 220, 433, 390, 483));
        row.children.add(node("", "TextView", "已有消息", 220, 504, 390, 535));
        row.children.add(node("", "TextView", "今天", 800, 435, 900, 483));
        list.children.add(row);
        UiNode panel = node("l_8", "RecyclerView", "", 0, 579, 1080, 1618);
        root.children.add(panel);
        if (WeComLayout.conversationList(root) != list || WeComLayout.conversationLabels(list).size() != 1
                || !"测试群".equals(WeComLayout.conversationLabels(list).get(0).text)) {
            throw new AssertionError("Expected the conversation title, excluding toolbar, preview and side panel");
        }
        root.children.remove(0);
        if (WeComLayout.conversationList(root) != null) throw new AssertionError("Wrong WeCom tab");
        routeRegression();
        nonGroupClassification();
        newMessageSignals();
        resumeChecksOriginalChatAndDraft();
        sendPageRecoveryIsBounded();
        directMentionEvidence();
        workMessageGuideRegression();
        System.out.println("WeCom conversation layout checks passed");
    }

    private static void routeRegression() {
        Map<String, String> prior = Collections.singletonMap("测试群", "测试群(3)");
        Map<String, String> current = Collections.singletonMap("另一群", "另一群(4)");
        Map<String, Integer> counts = new HashMap<>();
        counts.put("测试群", 1);
        counts.put("另一群", 1);
        if (!"测试群(3)".equals(WeComLayout.routesAfterRound(prior, current, counts,
                Collections.singleton("测试群")).get("测试群"))) {
            throw new AssertionError("REG-167 a transient open timeout must retain a unique known route");
        }
        if (WeComLayout.routesAfterRound(prior, current, counts, Collections.emptySet()).containsKey("测试群")) {
            throw new AssertionError("REG-167 an opened mismatching conversation invalidates the route");
        }
        for (int count : new int[]{0, 2}) {
            counts.put("测试群", count);
            if (WeComLayout.routesAfterRound(prior, current, counts, Collections.singleton("测试群")).containsKey("测试群")) {
                throw new AssertionError("REG-167 missing or ambiguous route must be removed");
            }
        }
    }

    private static void nonGroupClassification() {
        UiNode screen = node("", "FrameLayout", "", 0, 0, 1080, 2280);
        UiNode header = node("o6j", "TextView", "测试联系人", 300, 130, 800, 200);
        screen.children.add(header);
        screen.children.add(node("j62", "ListView", "", 0, 234, 1080, 1980));
        UiNode composer = node("iu5", "EditText", "", 150, 2030, 804, 2100);
        composer.editable = true;
        screen.children.add(composer);
        if (!WeComLayout.confirmedNonGroup(screen, "测试联系人")
                || WeComLayout.confirmedNonGroup(screen, "另一会话")) {
            throw new AssertionError("Only the exact opened direct conversation should be remembered");
        }
        header.text = "测试联系人(3)";
        if (WeComLayout.confirmedNonGroup(screen, "测试联系人")) throw new AssertionError("A group must not be excluded");
        header.text = "测试联系人";
        screen.children.remove(composer);
        if (WeComLayout.confirmedNonGroup(screen, "测试联系人")) {
            throw new AssertionError("An incomplete page must not be remembered");
        }
        UiNode row = node("row", "FrameLayout", "", 0, 380, 1080, 594);
        UiNode title = node("title", "TextView", "测试联系人", 220, 430, 400, 480);
        row.children.add(title);
        UiNode preview = node("preview", "TextView", "之前的消息", 220, 500, 800, 550);
        row.children.add(preview);
        String prior = WeComLayout.nonGroupRowSignature(row, title);
        preview.text = "一条新消息";
        if (prior == null || !prior.equals(WeComLayout.nonGroupRowSignature(row, title))) {
            throw new AssertionError("A new message preview must not invalidate a known direct conversation");
        }
        row.children.add(node("badge", "TextView", "全员", 420, 430, 500, 480));
        if (WeComLayout.nonGroupRowSignature(row, title) != null) {
            throw new AssertionError("Group evidence in the list overrides a prior exclusion");
        }
    }

    private static void newMessageSignals() {
        UiNode row = node("row", "FrameLayout", "", 0, 380, 1080, 594);
        UiNode title = node("title", "TextView", "测试群", 220, 430, 400, 480);
        UiNode preview = node("preview", "TextView", "测试成员: 已有正文", 220, 500, 800, 550);
        UiNode time = node("time", "TextView", "刚刚", 840, 430, 1000, 480);
        Collections.addAll(row.children, title, preview, time);
        String prior = WeComLayout.preview(row, title);
        if (!"测试成员: 已有正文".equals(prior) || !WeComLayout.shouldRead(row, title, null, 0, Long.MAX_VALUE)) {
            throw new AssertionError("A newly discovered conversation needs an initial read");
        }
        time.text = "2分钟前";
        if (WeComLayout.shouldRead(row, title, prior, 0, Long.MAX_VALUE)) throw new AssertionError("Time label changes are not new messages");
        preview.text = "测试成员: 新正文";
        if (!WeComLayout.shouldRead(row, title, prior, 0, Long.MAX_VALUE)) throw new AssertionError("New preview needs a read");
        preview.text = prior;
        UiNode unread = node("unread", "TextView", "2", 155, 408, 200, 450);
        row.children.add(unread);
        if (!WeComLayout.shouldRead(row, title, prior, 0, Long.MAX_VALUE)) throw new AssertionError("Unread can repeat the same preview");
        unread.visible = false;
        if (WeComLayout.shouldRead(row, title, prior, 0, Long.MAX_VALUE)) throw new AssertionError("Ignore a hidden unread badge");
        long retryAfter = 300000;
        if (WeComLayout.shouldRead(row, title, prior, 15000, retryAfter)
                || !WeComLayout.shouldRead(row, title, prior, retryAfter, retryAfter)) {
            throw new AssertionError("REG-178 unchanged history gaps retry only after their delay");
        }
        preview.text = "测试成员: 等待期间的新消息";
        if (!WeComLayout.shouldRead(row, title, prior, 15000, retryAfter)) {
            throw new AssertionError("REG-178 a new preview overrides the history retry delay");
        }
    }

    private static void resumeChecksOriginalChatAndDraft() {
        UiNode screen = node("", "FrameLayout", "", 0, 0, 1080, 2280);
        UiNode header = node("o6j", "TextView", "测试群(3)", 300, 130, 800, 200);
        UiNode composer = node("iu5", "EditText", "待核对草稿@", 150, 2030, 804, 2100);
        composer.editable = true;
        Collections.addAll(screen.children, header, node("j62", "ListView", "", 0, 234, 1080, 1980), composer);
        if (WeComLayout.draftState(WeComLayout.identify(screen), "测试群", "测试群(3)") != WeComLayout.DraftState.DRAFT) {
            throw new AssertionError("REG-176 existing draft must hold the queue");
        }
        composer.text = "";
        if (WeComLayout.draftState(WeComLayout.identify(screen), "测试群", "测试群(3)") != WeComLayout.DraftState.EMPTY
                || WeComLayout.draftState(WeComLayout.identify(screen), "测试群", "测试群(4)") != WeComLayout.DraftState.WRONG_CHAT) {
            throw new AssertionError("REG-176 recovery requires the same verified group and an empty composer");
        }
    }

    private static void sendPageRecoveryIsBounded() {
        WeComSendRecovery recovery = new WeComSendRecovery();
        for (int attempt = 0; attempt < 3; attempt++) {
            if (!recovery.retryRead()) throw new AssertionError("REG-176 transient reads get another attempt");
        }
        if (recovery.retryRead()
                || recovery.mayReopen(false, WeComLayout.DraftState.EMPTY)
                || recovery.mayReopen(true, WeComLayout.DraftState.DRAFT)
                || recovery.mayReopen(true, WeComLayout.DraftState.WRONG_CHAT)
                || !recovery.mayReopen(true, WeComLayout.DraftState.EMPTY)) {
            throw new AssertionError("REG-176 reopen requires a verified empty composer before click reservation");
        }
        recovery.reopened();
        recovery.readSucceeded();
        if (recovery.mayReopen(true, WeComLayout.DraftState.EMPTY) || !recovery.retryRead()) {
            throw new AssertionError("REG-176 a stable read resets only the read retry budget");
        }
        recovery.reset();
        if (!recovery.mayReopen(true, WeComLayout.DraftState.EMPTY)) {
            throw new AssertionError("REG-176 a new task gets its own reopen budget");
        }
    }

    private static void directMentionEvidence() {
        UiNode row = node("row", "FrameLayout", "", 0, 380, 1080, 594);
        UiNode unread = node("lb8", "TextView", "1", 158, 390, 210, 442);
        UiNode preview = node("n45", "TextView", "[有人@我]\u00a0测试成员:\u00a0测试正文@测试账号\u00a0",
                222, 493, 948, 547);
        Collections.addAll(row.children, unread, preview);
        String hint = WeComLayout.directMentionPreview(row);
        if (!WeComLayout.matchesDirectMention(hint, "测试成员", "测试正文@测试账号\u00a0", "incoming")
                || WeComLayout.matchesDirectMention(hint, "其他成员", "测试正文@测试账号", "incoming")
                || WeComLayout.matchesDirectMention(hint, "测试成员", "不同正文@测试账号", "incoming")
                || WeComLayout.matchesDirectMention(hint, "测试成员", "测试正文@测试账号", "outgoing")
                || WeComLayout.matchesDirectMention("测试成员: 测试正文@所有人", "测试成员", "测试正文@所有人", "incoming")) {
            throw new AssertionError("REG-173 a mention must match the actual sender and complete incoming text");
        }
        unread.text = "2";
        if (WeComLayout.directMentionPreview(row) != null) throw new AssertionError("Multiple unread messages are ambiguous");
        unread.text = "1";
        preview.text = "测试成员:\u00a0[有人@我] 测试正文@测试账号";
        if (WeComLayout.directMentionPreview(row) != null) throw new AssertionError("A literal prefix in the body is not a badge");
    }

    private static void workMessageGuideRegression() {
        UiNode row = node("row", "FrameLayout", "", 0, 812, 1080, 1026);
        UiNode title = node("title", "TextView", "接收微信中的工作消息", 220, 858, 720, 914);
        UiNode description = node("preview", "TextView", "选择微信中的工作群，使用企业微信收发…", 220, 930, 920, 979);
        Collections.addAll(row.children, title, description);
        if (!WeComLayout.isWorkMessageGuide(row, title)) {
            throw new AssertionError("REG-169 skip the built-in guide before opening");
        }
        description.description = description.text;
        description.text = "";
        if (!WeComLayout.isWorkMessageGuide(row, title)) {
            throw new AssertionError("REG-169 the guide caption can be a content description");
        }
        description.description = "";
        description.text = "测试成员：已有文字";
        if (WeComLayout.isWorkMessageGuide(row, title)) throw new AssertionError("REG-169 preserve same-name chat");
        description.text = "选择微信中的工作群，使用企业微信收发…";
        title.text = "别的群";
        if (WeComLayout.isWorkMessageGuide(row, title)) throw new AssertionError("REG-169 require exact guide title");
        title.text = "接收微信中的工作消息";
        row.children.add(node("badge", "TextView", "全员", 728, 860, 800, 913));
        if (WeComLayout.isWorkMessageGuide(row, title)) throw new AssertionError("REG-169 group marker takes priority");
        UiNode destination = node("", "FrameLayout", "", 0, 0, 1080, 2280);
        destination.children.add(node("button", "Button", "前往微信选择群聊", 180, 1620, 900, 1760));
        if (!WeComLayout.confirmedNonGroup(destination, title.text)
                || WeComLayout.confirmedNonGroup(destination, "同名以外的会话")) {
            throw new AssertionError("REG-169 remember the confirmed guide page for the matching entry");
        }
        destination.children.add(node("j62", "ListView", "", 0, 234, 1080, 1980));
        if (WeComLayout.confirmedNonGroup(destination, title.text)) {
            throw new AssertionError("REG-169 visible chat content overrides the guide button");
        }
    }

    private static UiNode node(String id, String type, String text, int left, int top, int right, int bottom) {
        UiNode node = new UiNode();
        node.id = id.isEmpty() ? "" : MessageExtractor.PREFIX + id;
        node.type = "android.widget." + type;
        node.text = text;
        node.left = left; node.top = top; node.right = right; node.bottom = bottom;
        return node;
    }
}
