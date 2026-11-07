package local.mdnode.wecomprobe;

import java.util.List;

final class MessageFieldDump {
    private static final int MAX_LENGTH = 100000;

    private MessageFieldDump() { }

    static String render(UiNode root, MessageExtractor.Result scan) {
        List<UiNode> lists = root.find(MessageExtractor.PREFIX + "j62");
        if (lists.size() != 1 || !"READING".equals(scan.status)) return "等待一次已核对的群扫描";
        UiNode list = lists.get(0);
        StringBuilder out = new StringBuilder("群 ").append(value(scan.group)).append("\n解析记录 ")
                .append(scan.messages.size()).append(" 条，跳过 ").append(scan.skipped).append(" 条\n");
        for (MessageExtractor.Message message : scan.messages) {
            if (out.length() >= MAX_LENGTH) break;
            out.append("正文 ").append(value(message.text)).append("\n发送人 ").append(value(message.sender))
                    .append(" 方向 ").append(value(message.direction)).append(" 时间 ").append(value(message.displayTime)).append("\n");
        }
        out.append("\n消息列表原始字段\n");
        appendTree(list, "列表", out);
        out.append("\n消息区外层文字及描述节点\n");
        appendNearby(root, list, "窗口", out);
        if (out.length() > MAX_LENGTH) return out.substring(0, MAX_LENGTH) + "\n…字段页已达显示上限";
        return out.toString();
    }

    private static void appendTree(UiNode node, String path, StringBuilder out) {
        if (out.length() >= MAX_LENGTH) return;
        appendNode(node, path, out);
        for (int i = 0; i < node.children.size(); i++) appendTree(node.children.get(i), path + "/" + i, out);
    }

    private static void appendNearby(UiNode node, UiNode list, String path, StringBuilder out) {
        if (node == list || out.length() >= MAX_LENGTH) return;
        if (node.visible && node.hasArea() && !node.editable && node.top >= list.top && node.bottom <= list.bottom
                && node.left >= list.left && node.right <= list.right && (!node.text.isEmpty() || !node.description.isEmpty())) {
            appendNode(node, path, out);
        }
        for (int i = 0; i < node.children.size(); i++) appendNearby(node.children.get(i), list, path + "/" + i, out);
    }

    private static void appendNode(UiNode node, String path, StringBuilder out) {
        out.append(path).append("  ").append(value(node.id)).append(' ').append(value(node.type))
                .append("\n  text=").append(value(node.text)).append("  desc=").append(value(node.description))
                .append("\n  bounds=[").append(node.left).append(',').append(node.top).append("][")
                .append(node.right).append(',').append(node.bottom).append("]  rowIndex=").append(node.rowIndex)
                .append("\n  visible=").append(node.visible).append("  enabled=").append(node.enabled)
                .append("  editable=").append(node.editable).append("  hint=").append(node.hint)
                .append("  clickable=").append(node.clickable).append("  longClickable=").append(node.longClickable)
                .append("  scrollable=").append(node.scrollable).append("  focused=").append(node.focused)
                .append("  selected=").append(node.selected).append("\n");
    }

    private static String value(String text) {
        if (text == null) return "null";
        String sample = text.length() > 500 ? text.substring(0, 500) + "…" : text;
        return '"' + sample.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\"", "\\\"") + '"';
    }
}
