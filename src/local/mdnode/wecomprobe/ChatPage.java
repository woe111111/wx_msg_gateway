package local.mdnode.wecomprobe;

import java.util.ArrayList;
import java.util.List;

abstract class ChatPage {
    final UiNode root, list, composer;
    final String name, title;

    ChatPage(UiNode root, UiNode list, UiNode composer, String name, String title) {
        this.root = root;
        this.list = list;
        this.composer = composer;
        this.name = name;
        this.title = title;
    }

    static final class Row {
        final String sender, text, direction;
        Row(String sender, String text) { this(sender, text, "unknown"); }
        Row(String sender, String text, String direction) {
            this.sender = sender;
            this.text = text;
            this.direction = direction;
        }
    }

    abstract List<Row> messages();
    abstract int countBody(String text);

    boolean emptyComposer() { return composer.text.isEmpty() || composer.hint; }

    UiNode sendButton() {
        List<UiNode> buttons = new ArrayList<>();
        collectSend(root, buttons);
        return uniqueVisible(buttons);
    }

    private void collectSend(UiNode node, List<UiNode> buttons) {
        if (node != list && node.clickable && node.enabled && !node.editable
                && ("发送".equals(node.text) || "发送".equals(node.description))
                && node.left >= composer.right && node.top >= list.bottom
                && node.top < composer.bottom && node.bottom > composer.top) buttons.add(node);
        if (node != list) for (UiNode child : node.children) collectSend(child, buttons);
    }

    static UiNode uniqueVisible(List<UiNode> nodes) {
        UiNode only = null;
        for (UiNode node : nodes) {
            if (!node.visible || !node.hasArea()) continue;
            if (only != null) return null;
            only = node;
        }
        return only;
    }
}
