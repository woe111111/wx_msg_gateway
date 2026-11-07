package local.mdnode.wecomprobe;

import java.util.ArrayList;
import java.util.List;

/** Deliberately version-specific. Unknown content is skipped rather than guessed. */
final class MessageExtractor {
    static final String PREFIX = "com.tencent.wework:id/";
    static final String PROFILE = "wecom_84802";

    static final class Message {
        final String text, sender, direction, displayTime;
        Message(String text, String sender, String direction, String displayTime) {
            this.text = text; this.sender = sender; this.direction = direction; this.displayTime = displayTime;
        }
        String key() { return field(text) + field(sender) + field(direction); }
        private static String field(String value) { return value == null ? "-1:" : value.length() + ":" + value; }
    }

    static final class Result {
        String status = "WRONG_GROUP", group;
        int skipped;
        final List<Message> messages = new ArrayList<>();
        final List<String> skipDetails = new ArrayList<>();
        final List<String> senderDetails = new ArrayList<>();

        void skip(String reason) {
            skipped++;
            if (skipDetails.size() < 5 && !skipDetails.contains(reason)) skipDetails.add(reason);
        }
    }

    static Result extract(UiNode root, List<String> groups, long version) {
        Result result = new Result();
        if (version != 84802) { result.status = "UNSUPPORTED_LAYOUT"; return result; }
        List<UiNode> headers = new ArrayList<>();
        findHeaders(root, root.top + (root.bottom - root.top) / 5, groups, headers);
        if (headers.size() != 1) return result;
        UiNode header = headers.get(0);
        for (String group : groups) {
            if (ProbeText.isGroupTitle(header.text, group)) {
                if (result.group != null) return result; // Ambiguous configured titles.
                result.group = group;
            }
        }
        if (result.group == null) return result;
        List<UiNode> lists = root.find(PREFIX + "j62");
        List<UiNode> composers = root.find(PREFIX + "iu5");
        if (lists.size() != 1 || composers.size() != 1) {
            result.status = "UNSUPPORTED_LAYOUT"; return result;
        }
        UiNode list = lists.get(0), composer = composers.get(0);
        if (!list.visible || !list.hasArea() || !"android.widget.ListView".equals(list.type)
                || !composer.visible || !composer.editable || !composer.hasArea()
                || composer.top < root.top + (root.bottom - root.top) / 2
                || list.top < header.bottom || list.bottom > composer.top) {
            result.status = "UNSUPPORTED_LAYOUT"; return result;
        }
        result.status = "READING";
        for (UiNode row : list.children) {
            if (!(PREFIX + "cyi").equals(row.id) || !row.visible) continue;
            List<UiNode> bubbles = visibleNodes(row.find(PREFIX + "ixa"));
            if (bubbles.isEmpty()) {
                bubbles.addAll(visibleNodes(row.find(PREFIX + "ibi")));
                bubbles.addAll(visibleNodes(row.find(PREFIX + "ica")));
            }
            if (bubbles.size() != 1) { result.skip("可见气泡 " + bubbles.size() + " 个，行结构 " + shapes(row)); continue; }
            UiNode bubble = bubbles.get(0);
            List<UiNode> texts = visibleNodes(bubble.find(PREFIX + "ith"));
            if (texts.size() != 1) { result.skip("可见正文节点 " + texts.size() + " 个，气泡结构 " + shapes(bubble)); continue; }
            UiNode text = texts.get(0);
            if (text.editable || !"android.widget.TextView".equals(text.type) || text.text.trim().isEmpty()) {
                result.skip("正文类型或内容不符合文字规则 " + shapes(text));
                continue;
            }
            if (text.top < list.top || text.bottom > list.bottom || text.left < list.left || text.right > list.right) {
                result.skip("正文未完整显示在消息区");
                continue;
            }
            String sender = null;
            List<UiNode> names = visibleNodes(row.find(PREFIX + "j77"));
            if (names.size() == 1) sender = singleVisibleText(names.get(0));
            String direction = "unknown";
            List<UiNode> avatars = visibleNodes(row.find(PREFIX + "j3b"));
            if (avatars.size() == 1) {
                UiNode avatar = avatars.get(0);
                if (avatar.right <= bubble.left) {
                    direction = "incoming";
                    if (sender == null && names.isEmpty()) sender = alignedName(row, bubble, text, avatar);
                    if (sender == null && result.senderDetails.size() < 3) {
                        String detail = "入站姓名未定位，行结构 " + shapes(row);
                        if (!result.senderDetails.contains(detail)) result.senderDetails.add(detail);
                    }
                }
                else if (avatar.left >= bubble.right) direction = "outgoing";
            }
            String time = null;
            List<UiNode> times = row.find(PREFIX + "iuc");
            if (times.size() == 1 && times.get(0).visible && !times.get(0).text.trim().isEmpty()) {
                time = times.get(0).text;
            }
            result.messages.add(new Message(text.text, sender, direction, time));
        }
        return result;
    }

    private static String alignedName(UiNode row, UiNode bubble, UiNode body, UiNode avatar) {
        List<String> candidates = new ArrayList<>();
        collectAlignedNames(row, body.top, bubble.left, avatar, candidates);
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private static void collectAlignedNames(UiNode node, int bodyTop, int bubbleLeft, UiNode avatar,
                                            List<String> candidates) {
        int tolerance = Math.max(1, (avatar.right - avatar.left) / 3);
        if (node.visible && node.hasArea() && !node.editable && "android.widget.TextView".equals(node.type)
                && !node.id.equals(PREFIX + "iuc") && !node.id.equals(PREFIX + "ith")
                && node.top >= avatar.top - tolerance && node.bottom <= Math.min(bodyTop, avatar.bottom)
                && node.left >= avatar.right && Math.abs(node.left - bubbleLeft) <= tolerance) {
            String label = node.text.trim();
            if (!label.isEmpty() && !candidates.contains(label)) candidates.add(label);
        }
        for (UiNode child : node.children) collectAlignedNames(child, bodyTop, bubbleLeft, avatar, candidates);
    }

    private static List<UiNode> visibleNodes(List<UiNode> nodes) {
        List<UiNode> visible = new ArrayList<>();
        for (UiNode node : nodes) if (node.visible && node.hasArea()) visible.add(node);
        return visible;
    }

    private static String shapes(UiNode root) {
        List<String> result = new ArrayList<>();
        collectShapes(root, result);
        return result.toString();
    }

    private static void collectShapes(UiNode node, List<String> result) {
        if (result.size() >= 8) return;
        String id = node.id.substring(node.id.lastIndexOf('/') + 1);
        String type = node.type.substring(node.type.lastIndexOf('.') + 1);
        result.add(id + "/" + type + (node.text.isEmpty() ? "" : "(有文字)"));
        for (UiNode child : node.children) collectShapes(child, result);
    }

    private static void findHeaders(UiNode node, int maxY, List<String> groups, List<UiNode> result) {
        if (node.visible && node.hasArea() && node.bottom <= maxY && !node.editable
                && "android.widget.TextView".equals(node.type)) {
            for (String group : groups) {
                if (ProbeText.isGroupTitle(node.text, group)) { result.add(node); break; }
            }
        }
        for (UiNode child : node.children) findHeaders(child, maxY, groups, result);
    }

    private static String singleVisibleText(UiNode node) {
        List<String> texts = new ArrayList<>();
        visibleText(node, texts);
        return texts.size() == 1 ? texts.get(0) : null;
    }

    private static void visibleText(UiNode node, List<String> result) {
        if (node.visible && !node.editable && "android.widget.TextView".equals(node.type)
                && !node.text.trim().isEmpty()) result.add(node.text);
        for (UiNode child : node.children) visibleText(child, result);
    }
}
