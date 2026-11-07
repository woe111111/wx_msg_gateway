package local.mdnode.wecomprobe;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class MessageReaderTest {
    private static int checks;
    public static void main(String[] args) {
        check(ReaderConfig.groups("测试群\n另一群\n测试群\n").size() == 2, "group allowlist deduplicated");
        try { ReaderConfig.groups("  \n"); throw new AssertionError("empty config accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        UiNode root = screen();
        UiNode list = root.find(id("j62")).get(0);
        list.children.add(row("MDTEST-test0001", null, false, "下午 12:18", 1000));
        MessageExtractor.Result scan = scan(root, "测试群");
        check("READING".equals(scan.status) && scan.messages.size() == 1, "captured native outgoing layout");
        MessageExtractor.Message message = scan.messages.get(0);
        check(message.sender == null && "outgoing".equals(message.direction), "own sender stays null");
        check("下午 12:18".equals(message.displayTime), "time remains raw label");
        check("WRONG_GROUP".equals(scan(root, "测试").status), "similar group rejected");
        check("UNSUPPORTED_LAYOUT".equals(MessageExtractor.extract(root, Arrays.asList("测试群"), 1).status),
                "unsupported version rejected");
        check("测试群".equals(MessageExtractor.extract(root, Arrays.asList("另一群", "测试群"), 84802).group),
                "configured group allowlist");

        UiNode replyScreen = screen();
        UiNode replyList = replyScreen.find(id("j62")).get(0);
        replyList.children.add(row("reply-test？", "测试成员", true, null, 800));
        replyList.children.add(row("reply-test？", null, false, null, 1100));
        WeComLayout reply = WeComLayout.identify(replyScreen);
        check(reply != null && reply.countBody("reply-test？ \n") == 1,
                "REG-166 compare rendered reply with trailing whitespace and ignore incoming match");
        check(reply.countBody("reply-test?") == 0, "REG-166 retain significant punctuation");
        check(reply.countBody("reply- test？") == 0, "REG-166 retain internal whitespace");

        UiNode incoming = row("第一行\n第二行", "测试成员", true, null, 1300);
        list.children.add(incoming);
        scan = scan(root, "测试群");
        check(scan.messages.size() == 2 && "第一行\n第二行".equals(scan.messages.get(1).text), "multiline preserved");
        check("测试成员".equals(scan.messages.get(1).sender)
                && "incoming".equals(scan.messages.get(1).direction), "synthetic incoming sender scoped to row");
        UiNode hidden = node("ith", "android.widget.TextView", "缓存正文", 159, 1300, 903, 1450);
        hidden.visible = false;
        incoming.find(id("ixa")).get(0).children.add(hidden);
        check(scan(root, "测试群").messages.size() == 2, "hidden duplicate must not suppress visible text");
        incoming.find(id("ixa")).get(0).children.remove(hidden);
        UiNode incomingBubble = incoming.find(id("ixa")).get(0);
        incomingBubble.id = id("ica");
        UiNode outgoingBubble = list.children.get(0).find(id("ixa")).get(0);
        outgoingBubble.id = id("ibi");
        MessageExtractor.Result alternate = scan(root, "测试群");
        check(alternate.messages.size() == 2 && alternate.skipped == 0, "observed ibi and ica wrappers expose ordinary text");
        check("outgoing".equals(alternate.messages.get(0).direction)
                && "incoming".equals(alternate.messages.get(1).direction), "alternate wrappers preserve direction");
        incomingBubble.id = outgoingBubble.id = id("ixa");
        check(scan.messages.get(1).displayTime == null, "do not invent timestamp for next row");
        incoming.find(id("j77")).get(0).children.clear();
        check(scan(root, "测试群").messages.get(1).sender == null, "do not inherit sender");
        incoming.children.remove(incoming.find(id("j77")).get(0));
        UiNode alternateName = node("nick_alt", "android.widget.TextView", "测试成员", 177, 1290, 320, 1320);
        UiNode incomingBody = incoming.find(id("ith")).get(0);
        incomingBody.top = 1330;
        incoming.children.add(alternateName);
        check("测试成员".equals(scan(root, "测试群").messages.get(1).sender), "read same-row name above body beside avatar");
        UiNode ambiguousName = node("nick_alt2", "android.widget.TextView", "另一成员", 178, 1291, 340, 1322);
        incoming.children.add(ambiguousName);
        check(scan(root, "测试群").messages.get(1).sender == null, "ambiguous same-row names stay unknown");
        incoming.children.remove(alternateName);
        incoming.children.remove(ambiguousName);

        // Actual rich-text shape has a custom container with no plain-text body node.
        UiNode rich = row("ignored", "测试机器人", true, null, 1600);
        UiNode bubble = rich.find(id("ixa")).get(0);
        bubble.children.clear(); bubble.children.add(node("fnp", "android.widget.FrameLayout", "", 170, 1600, 900, 1750));
        list.children.add(rich);
        scan = scan(root, "测试群");
        check(scan.messages.size() == 2 && scan.skipped == 1, "rich content not mistaken for sender/body");
        check(scan.skipDetails.get(0).contains("fnp/FrameLayout") && !scan.skipDetails.get(0).contains("测试机器人"),
                "skip detail describes structure without message content");
        UiNode pinned = node("ith", "android.widget.TextView", "置顶正文不是消息", 170, 220, 900, 300);
        root.children.add(pinned);
        check(scan(root, "测试群").messages.size() == 2, "pinned text outside list excluded");
        root.find(id("iu5")).get(0).text = "草稿不是消息";
        check(scan(root, "测试群").messages.size() == 2, "composer draft excluded");
        UiNode overlay = node("label_layer", "android.view.View", "", 170, 980, 540, 1000);
        overlay.description = "气泡外层的测试描述";
        root.children.add(overlay);
        String fields = MessageFieldDump.render(root, scan(root, "测试群"));
        check(fields.contains("/ith") && fields.contains("气泡外层的测试描述") && fields.contains("bounds="),
                "field dump includes row and separately positioned descriptions");
        check(!fields.contains("草稿不是消息") && !fields.contains("置顶正文不是消息"), "field dump excludes composer and unrelated regions");
        incoming.find(id("ith")).get(0).bottom = 2100;
        check(scan(root, "测试群").messages.size() == 1, "partially clipped body skipped");
        list.visible = false;
        check("UNSUPPORTED_LAYOUT".equals(scan(root, "测试群").status), "hidden message list rejected");

        MessageTracker.Result first = reconcile(Collections.emptyList(), true, "A", "A", "B");
        check(first.added.size() == 3 && "baseline".equals(first.added.get(0).kind), "baseline retains duplicate rows");
        MessageTracker.Result same = reconcile(first.checkpoint, false, "A", "A", "B");
        check(same.added.isEmpty(), "same viewport deduplicated with repeated text");
        check(first.checkpoint.get(1).id.equals(same.checkpoint.get(1).id), "stable local IDs");
        MessageTracker.Result append = reconcile(same.checkpoint, false, "A", "A", "B", "C");
        check(append.added.size() == 1 && "C".equals(append.added.get(0).message.text), "append one row");
        MessageTracker.Result scroll = reconcile(append.checkpoint, false, "B", "C", "D");
        check(scroll.added.size() == 1 && !scroll.ambiguous, "unique scrolling overlap");
        MessageTracker.Result disjoint = reconcile(scroll.checkpoint, false, "X", "Y");
        check(disjoint.added.size() == 2 && disjoint.ambiguous, "disjoint viewport marked uncertain");
        MessageTracker.Result one = reconcile(Collections.emptyList(), true, "A");
        MessageTracker.Result two = reconcile(one.checkpoint, false, "A", "A");
        check(two.added.size() == 2 && two.ambiguous, "ambiguous identical append preserved rather than silently dropped");
        MessageTracker.Result sender1 = MessageTracker.reconcile(Collections.emptyList(),
                Arrays.asList(new MessageExtractor.Message("A", "甲", "incoming", null)), true);
        MessageTracker.Result sender2 = MessageTracker.reconcile(sender1.checkpoint,
                Arrays.asList(new MessageExtractor.Message("A", "乙", "incoming", null)), false);
        check(sender2.added.size() == 1, "same text different sender not merged");
        System.out.println("Message reader tests passed: " + checks);
    }

    private static MessageExtractor.Result scan(UiNode root, String group) {
        return MessageExtractor.extract(root, Arrays.asList(group), 84802);
    }
    private static MessageTracker.Result reconcile(List<MessageTracker.Entry> previous, boolean baseline, String... text) {
        java.util.ArrayList<MessageExtractor.Message> messages = new java.util.ArrayList<>();
        for (String value : text) messages.add(new MessageExtractor.Message(value, null, "outgoing", null));
        return MessageTracker.reconcile(previous, messages, baseline);
    }
    private static String id(String value) { return MessageExtractor.PREFIX + value; }
    private static UiNode node(String id, String type, String text, int l, int t, int r, int b) {
        UiNode n = new UiNode(); n.id = id(id); n.type = type; n.text = text;
        n.left=l; n.top=t; n.right=r; n.bottom=b; return n;
    }
    private static UiNode screen() {
        UiNode root = node("root", "android.widget.FrameLayout", "", 0, 0, 1080, 2160);
        root.children.add(node("title", "android.widget.TextView", "测试群(5)", 300, 120, 800, 200));
        root.children.add(node("j62", "android.widget.ListView", "", 0, 440, 1080, 1980));
        UiNode composer = node("iu5", "android.widget.EditText", "", 150, 2030, 804, 2100);
        composer.editable=true; root.children.add(composer); return root;
    }
    private static UiNode row(String text, String sender, boolean incoming, String time, int top) {
        UiNode row = node("cyi", "android.widget.RelativeLayout", "", 0, top-100, 1080, top+200);
        if (time != null) row.children.add(node("iuc", "android.widget.TextView", time, 400, top-100, 650, top-60));
        if (sender != null) {
            UiNode name = node("j77", "android.view.ViewGroup", "", 170, top-50, 700, top);
            name.children.add(node("", "android.widget.TextView", sender, 170, top-50, 700, top));
            row.children.add(name);
        }
        UiNode bubble = node("ixa", "android.widget.LinearLayout", "", incoming?159:424, top, incoming?903:921, top+150);
        bubble.children.add(node("ith", "android.widget.TextView", text, bubble.left, top, bubble.right, top+150));
        row.children.add(bubble);
        row.children.add(node("j3b", "android.widget.ImageView", "", incoming?30:936, top, incoming?144:1050, top+114));
        return row;
    }
    private static void check(boolean value, String scenario) {
        checks++; if (!value) throw new AssertionError(scenario);
    }
}
