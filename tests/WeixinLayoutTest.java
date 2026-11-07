package local.mdnode.wecomprobe;

import java.util.Collections;

public final class WeixinLayoutTest {
    public static void main(String[] args) {
        UiNode root = node("", "FrameLayout", "", 0, 0, 1080, 2060);
        UiNode title = node("", "TextView", "测试群(2) \u00a0", 401, 126, 667, 184);
        UiNode list = node("bp0", "androidx.recyclerview.widget.RecyclerView", "", 0, 220, 1080, 1997);
        UiNode composer = node("bkk", "android.widget.EditText", "", 130, 2020, 842, 2060);
        composer.editable = true;
        Collections.addAll(root.children, title, list, composer);

        UiNode system = node("", "FrameLayout", "", 0, 400, 1080, 480);
        system.children.add(node("bkl", "TextView", "群名变更提示", 200, 410, 880, 470));
        UiNode message = node("bn1", "FrameLayout", "", 0, 500, 1080, 620);
        message.children.add(node("brc", "TextView", "成员 A", 180, 510, 500, 550));
        message.children.add(node("bkl", "TextView", "测试", 180, 560, 350, 610));
        Collections.addAll(list.children, system, message);

        WeixinLayout chat = WeixinLayout.identify(root);
        check(chat != null, "observed group layout");
        check(chat.messages().size() == 1 && "成员 A".equals(chat.messages().get(0).sender)
                && "测试".equals(chat.messages().get(0).text), "ordinary text excludes system row");
        check(chat.emptyComposer() && !chat.containsBody("新消息"), "empty draft and no pre-existing test bubble");
        composer.text = "新消息";
        check(!chat.emptyComposer() && !chat.containsBody("新消息"), "draft does not count as sent");

        UiNode misleading = node("", "Button", "发送", 865, 500, 1010, 580);
        misleading.clickable = misleading.enabled = true;
        message.children.add(misleading);
        check(chat.sendButton() == null, "ignore send label inside message list");
        UiNode button = node("", "Button", "发送", 866, 2014, 1030, 2060);
        button.clickable = button.enabled = true;
        root.children.add(button);
        check(chat.sendButton() == button, "unique send control next to composer");
        UiNode duplicate = node("", "Button", "发送", 867, 2014, 1030, 2060);
        duplicate.clickable = duplicate.enabled = true;
        root.children.add(duplicate);
        check(chat.sendButton() == null, "ambiguous send controls block click");
        title.text = "单聊";
        check(WeixinLayout.identify(root).name == null, "individual chat is not a group");
        title.text = "测试群(2)";
        composer.editable = false;
        check(WeixinLayout.identify(root).messages().size() == 1, "a readonly composer still allows reading");

        UiNode home = node("", "FrameLayout", "", 0, 0, 1080, 2060);
        UiNode conversations = node("variant_list", "android.widget.ListView", "", 0, 220, 1080, 2000);
        conversations.children.add(node("kbq", "View", "测试群", 205, 258, 868, 320));
        home.children.add(conversations);
        check(WeixinLayout.conversationList(home) == conversations, "fallback list requires known conversation labels");
        check(WeixinLayout.diagnosis(home).contains("会话 1"), "diagnosis reports selector evidence without message text");
        System.out.println("Weixin layout checks passed");
    }

    private static UiNode node(String id, String type, String text, int left, int top, int right, int bottom) {
        UiNode node = new UiNode();
        node.id = id.isEmpty() ? "" : WeixinLayout.PREFIX + id;
        node.type = type;
        node.text = text;
        node.left = left;
        node.top = top;
        node.right = right;
        node.bottom = bottom;
        return node;
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
