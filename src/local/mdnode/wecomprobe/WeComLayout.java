package local.mdnode.wecomprobe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WeComLayout extends ChatPage {
    static final String PACKAGE = "com.tencent.wework";
    static final int VERSION = 84802;
    private static final String WORK_MESSAGE_GUIDE = "接收微信中的工作消息";
    private static final String ID = MessageExtractor.PREFIX;
    private static final Pattern GROUP_TITLE = Pattern.compile("(.+?)\\s*(?:\\([0-9]+\\)|（[0-9]+）)");

    private WeComLayout(UiNode root, UiNode list, UiNode composer, String name, String title) {
        super(root, list, composer, name, title);
    }

    static WeComLayout identify(UiNode root) {
        UiNode list = uniqueVisible(root.find(ID + "j62"));
        UiNode composer = uniqueVisible(root.find(ID + "iu5"));
        if (list == null || composer == null || list.bottom > composer.top || !composer.editable
                || !list.type.endsWith("ListView")) return null;
        UiNode header = groupHeader(root, list.top);
        String title = header == null ? "" : header.text.trim();
        Matcher group = GROUP_TITLE.matcher(title);
        String name = header != null && header.bottom <= list.top && group.matches() ? group.group(1).trim() : null;
        return new WeComLayout(root, list, composer, name, title);
    }

    private static UiNode groupHeader(UiNode root, int listTop) {
        UiNode exact = uniqueVisible(root.find(ID + "o6j"));
        if (exact != null && exact.bottom <= listTop && GROUP_TITLE.matcher(exact.text.trim()).matches()) return exact;
        List<UiNode> headers = new ArrayList<>();
        findGroupHeaders(root, Math.min(listTop, root.top + (root.bottom - root.top) / 5), headers);
        return uniqueVisible(headers);
    }

    private static void findGroupHeaders(UiNode node, int maxY, List<UiNode> headers) {
        if (node.visible && node.hasArea() && !node.editable && "android.widget.TextView".equals(node.type)
                && node.bottom <= maxY && GROUP_TITLE.matcher(node.text.trim()).matches()) headers.add(node);
        for (UiNode child : node.children) findGroupHeaders(child, maxY, headers);
    }

    static boolean confirmedNonGroup(UiNode root, String expectedTitle) {
        UiNode list = uniqueVisible(root.find(ID + "j62"));
        UiNode header = uniqueVisible(root.find(ID + "o6j"));
        if (WORK_MESSAGE_GUIDE.equals(expectedTitle) && root.find(ID + "j62").isEmpty()
                && root.find(ID + "iu5").isEmpty() && hasGuideAction(root)) return true;
        if (list == null || header == null || !list.type.endsWith("ListView") || header.bottom > list.top
                || !expectedTitle.equals(header.text.trim()) || groupHeader(root, list.top) != null) return false;
        // The built-in notice thread is read-only. Unknown screens with no composer stay unclassified.
        return identify(root) != null || "企业微信团队".equals(expectedTitle);
    }

    static boolean isWorkMessageGuide(UiNode row, UiNode title) {
        return WORK_MESSAGE_GUIDE.equals(title.text.trim()) && !hasGroupBadge(row, title)
                && hasGuideCaption(row);
    }

    private static boolean hasGuideAction(UiNode node) {
        if (!node.visible) return false;
        String action = "前往微信选择群聊";
        if (node.hasArea() && (action.equals(node.text.trim()) || action.equals(node.description.trim()))) return true;
        for (UiNode child : node.children) if (hasGuideAction(child)) return true;
        return false;
    }

    private static boolean hasGuideCaption(UiNode node) {
        if (!node.visible) return false;
        String caption = (node.text + " " + node.description).replaceAll("[\\s\\u3000]+", "");
        if (caption.contains("选择微信中的工作群") && caption.contains("使用企业微信收发")) return true;
        for (UiNode child : node.children) if (hasGuideCaption(child)) return true;
        return false;
    }

    static boolean shouldRead(UiNode row, UiNode title, String previousPreview, long now, long retryAfter) {
        return previousPreview == null || hasUnread(row, title) || !previousPreview.equals(preview(row, title))
                || now >= retryAfter;
    }

    enum DraftState { WRONG_CHAT, DRAFT, EMPTY }

    static DraftState draftState(ChatPage chat, String name, String title) {
        if (chat == null || !name.equals(chat.name) || !title.equals(chat.title)) return DraftState.WRONG_CHAT;
        return chat.emptyComposer() ? DraftState.EMPTY : DraftState.DRAFT;
    }

    static String preview(UiNode row, UiNode title) {
        UiNode preview = findPreview(row, title, Math.max(1, (row.right - row.left) / 40));
        return preview == null ? "" : preview.text.trim();
    }

    static String directMentionPreview(UiNode row) {
        UiNode unread = uniqueVisible(row.find(ID + "lb8"));
        UiNode preview = uniqueVisible(row.find(ID + "n45"));
        if (unread == null || !"1".equals(unread.text) || preview == null) return null;
        String text = preview.text.replace('\u00a0', ' ');
        String badge = "[有人@我] ";
        return text.startsWith(badge) ? text.substring(badge.length()).trim() : null;
    }

    static boolean matchesDirectMention(String preview, String sender, String text, String direction) {
        if (preview == null || sender == null || sender.isEmpty() || !"incoming".equals(direction)
                || !text.contains("@") || text.contains("@所有人")) return false;
        return preview.equals(sender + ": " + text.replace('\u00a0', ' ').trim());
    }

    private static UiNode findPreview(UiNode node, UiNode title, int tolerance) {
        UiNode first = null;
        if (node.visible && node.hasArea() && node.type.endsWith("TextView")
                && node.top >= title.bottom && Math.abs(node.left - title.left) <= tolerance
                && !node.text.trim().isEmpty()) first = node;
        for (UiNode child : node.children) {
            UiNode candidate = findPreview(child, title, tolerance);
            if (candidate != null && (first == null || candidate.top < first.top)) first = candidate;
        }
        return first;
    }

    private static boolean hasUnread(UiNode node, UiNode title) {
        if (node != title && node.visible && node.hasArea() && node.left < title.left
                && node.top < title.bottom && node.bottom > title.top - (title.bottom - title.top)) {
            String badge = node.text.trim();
            String description = node.description.trim();
            if (badge.matches("[1-9][0-9]{0,2}\\+?") || "未读".equals(description)
                    || description.matches("[1-9][0-9]*\\+?\\s*条未读(?:消息)?")) return true;
        }
        for (UiNode child : node.children) if (hasUnread(child, title)) return true;
        return false;
    }

    static String nonGroupRowSignature(UiNode row, UiNode title) {
        if (hasGroupBadge(row, title)) return null;
        StringBuilder signature = new StringBuilder(row.id).append('|').append(row.type)
                .append('|').append(title.id);
        appendAvatarShape(row, title.left, signature);
        return signature.toString();
    }

    private static boolean hasGroupBadge(UiNode node, UiNode title) {
        if (node != title && node.visible && node.hasArea() && node.left >= title.right
                && node.top < title.bottom && node.bottom > title.top) {
            String caption = node.text.isEmpty() ? node.description : node.text;
            if ("全员".equals(caption) || "外部群".equals(caption) || "群聊".equals(caption)
                    || "客户群".equals(caption)) return true;
        }
        for (UiNode child : node.children) if (hasGroupBadge(child, title)) return true;
        return false;
    }

    private static void appendAvatarShape(UiNode node, int titleLeft, StringBuilder signature) {
        if (node.visible && node.hasArea() && node.right <= titleLeft && node.type.endsWith("ImageView")) {
            signature.append('|').append(node.id).append('|').append(node.description);
        }
        for (UiNode child : node.children) appendAvatarShape(child, titleLeft, signature);
    }

    @Override List<Row> messages() {
        List<Row> rows = new ArrayList<>();
        for (MessageExtractor.Message message : readMessages()) {
            rows.add(new Row("outgoing".equals(message.direction) ? "本机账号" : message.sender, message.text, message.direction));
        }
        return rows;
    }

    @Override int countBody(String text) {
        // The chat bubble can omit whitespace at the edges of what was submitted.
        String rendered = text.trim();
        int count = 0;
        for (MessageExtractor.Message message : readMessages()) {
            if ("outgoing".equals(message.direction) && rendered.equals(message.text.trim())) count++;
        }
        return count;
    }

    private List<MessageExtractor.Message> readMessages() {
        if (name == null) return Collections.emptyList();
        return MessageExtractor.extract(root, Collections.singletonList(name), VERSION).messages;
    }

    static Map<String, String> routesAfterRound(Map<String, String> previous, Map<String, String> verified,
                                               Map<String, Integer> counts, Set<String> unopened) {
        Map<String, String> result = new HashMap<>(verified);
        // An entry still present exactly once can survive a transient open timeout.
        for (String name : unopened) {
            if (counts.getOrDefault(name, 0) == 1 && previous.containsKey(name)) {
                result.putIfAbsent(name, previous.get(name));
            }
        }
        return result;
    }

    static UiNode conversationList(UiNode root) {
        if (!root.find(ID + "j62").isEmpty() || !root.find(ID + "iu5").isEmpty()) return null;
        int tabsTop = root.top + (root.bottom - root.top) * 4 / 5;
        UiNode messages = tab(root, "消息", tabsTop);
        if (messages == null || !hasMessageHeading(root, root.top + (root.bottom - root.top) / 6)) return null;
        UiNode list = uniqueVisible(root.find(ID + "db5"));
        return list != null && list.bottom <= messages.top && !conversationLabels(list).isEmpty() ? list : null;
    }

    private static boolean hasMessageHeading(UiNode node, int maxY) {
        if (node.visible && node.hasArea() && node.bottom <= maxY && "消息".equals(node.text)) return true;
        for (UiNode child : node.children) if (hasMessageHeading(child, maxY)) return true;
        return false;
    }

    private static UiNode tab(UiNode node, String name, int minY) {
        if (node.visible && node.hasArea() && node.top >= minY
                && (name.equals(node.text) || name.equals(node.description))) return node;
        for (UiNode child : node.children) {
            UiNode match = tab(child, name, minY);
            if (match != null) return match;
        }
        return null;
    }

    static List<UiNode> conversationLabels(UiNode list) {
        List<UiNode> labels = new ArrayList<>();
        int minRowHeight = (list.right - list.left) / 6;
        for (UiNode row : list.children) {
            if (row.bottom - row.top < minRowHeight || !row.visible || !row.hasArea() || row.bottom <= list.top || row.top >= list.bottom) continue;
            UiNode title = firstLabel(row, row, list);
            if (title != null && title.top >= list.top && title.bottom <= list.bottom) {
                labels.add(title);
            }
        }
        return labels;
    }

    static String chatDiagnosis(UiNode root, String expected) {
        List<String> details = new ArrayList<>();
        for (String id : new String[]{"j62", "iu5", "o6j"}) {
            for (UiNode node : root.find(ID + id)) {
                details.add(id + "/" + node.type + " " + node.top + "-" + node.bottom
                        + (node.editable ? " 可编辑" : "") + (node.visible ? "" : " 不可见"));
            }
        }
        List<UiNode> headers = new ArrayList<>();
        findGroupHeaders(root, root.top + (root.bottom - root.top) / 5, headers);
        return "控件 " + details + "，群标题 " + headers.size() + " 个，匹配入口 "
                + headers.stream().anyMatch(node -> ProbeText.isGroupTitle(node.text, expected));
    }

    // The title is the uppermost text in the conversation row's main column.
    private static UiNode firstLabel(UiNode node, UiNode row, UiNode list) {
        int width = list.right - list.left;
        UiNode best = null;
        if (node.visible && node.hasArea() && !node.editable && node.type.endsWith("TextView")
                && !node.text.trim().isEmpty() && node.top >= row.top && node.bottom <= row.bottom
                && node.left > list.left + width / 6 && node.left < list.left + width / 2) best = node;
        for (UiNode child : node.children) {
            UiNode label = firstLabel(child, row, list);
            if (label != null && (best == null || label.top < best.top
                    || (label.top == best.top && label.left < best.left))) best = label;
        }
        return best;
    }

    static String diagnosis(UiNode root) {
        int tabsTop = root.top + (root.bottom - root.top) * 4 / 5;
        List<String> details = new ArrayList<>();
        for (String id : new String[]{"db5", "l_8"}) {
            for (UiNode list : root.find(ID + id)) {
                details.add(id + " " + list.top + "-" + list.bottom + " / "
                        + list.children.size() + " 行 / " + conversationLabels(list).size() + " 入口");
            }
        }
        return "消息标签 " + (tab(root, "消息", tabsTop) != null)
                + "，消息标题 " + hasMessageHeading(root, root.top + (root.bottom - root.top) / 6)
                + "，聊天区 " + root.find(ID + "j62").size()
                + "，输入框 " + root.find(ID + "iu5").size()
                + "，列表控件 " + details;
    }
}
