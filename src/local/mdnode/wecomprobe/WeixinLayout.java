package local.mdnode.wecomprobe;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WeixinLayout extends ChatPage {
    static final String PACKAGE = "com.tencent.mm";
    static final int VERSION = 3220;
    static final String PREFIX = PACKAGE + ":id/";
    private static final Pattern GROUP_TITLE = Pattern.compile("(.+?)[（(]([0-9]+)[）)]");

    private WeixinLayout(UiNode root, UiNode list, UiNode composer, String name, String title) {
        super(root, list, composer, name, title);
    }

    static WeixinLayout identify(UiNode root) {
        UiNode list = uniqueVisible(root.find(PREFIX + "bp0"));
        UiNode composer = uniqueVisible(root.find(PREFIX + "bkk"));
        if (list == null || composer == null || list.bottom > composer.bottom
                || !list.type.endsWith("RecyclerView") || !composer.type.endsWith("EditText")) return null;
        List<String> titles = new ArrayList<>();
        collectTitle(root, list.top, titles);
        if (titles.size() != 1) return new WeixinLayout(root, list, composer, null, null);
        String title = titles.get(0);
        Matcher match = GROUP_TITLE.matcher(title);
        if (!match.matches()) return new WeixinLayout(root, list, composer, null, null);
        return new WeixinLayout(root, list, composer, match.group(1).trim(), title);
    }

    private static void collectTitle(UiNode node, int listTop, List<String> titles) {
        String title = node.text.replace('\u00a0', ' ').trim();
        if (node.visible && node.hasArea() && node.bottom <= listTop && !node.editable
                && GROUP_TITLE.matcher(title).matches()) titles.add(title);
        for (UiNode child : node.children) collectTitle(child, listTop, titles);
    }

    List<Row> messages() {
        List<Row> messages = new ArrayList<>();
        for (UiNode row : list.children) {
            UiNode body = uniqueVisible(row.find(PREFIX + "bkl"));
            if (body == null || body.text.trim().isEmpty() || body.top < list.top
                    || body.bottom > Math.min(list.bottom, composer.top)) continue;
            UiNode sender = uniqueVisible(row.find(PREFIX + "brc"));
            if (sender != null || hasAvatar(row)) {
                messages.add(new Row(sender == null ? null : sender.text, body.text));
            }
        }
        return messages;
    }

    private static boolean hasAvatar(UiNode node) {
        if (node.visible && node.type.endsWith("ImageView") && (node.id.equals(PREFIX + "bk1")
                || node.description.endsWith("头像"))) return true;
        for (UiNode child : node.children) if (hasAvatar(child)) return true;
        return false;
    }

    boolean containsBody(String text) {
        return countBody(text) > 0;
    }

    int countBody(String text) {
        int count = 0;
        for (UiNode body : list.find(PREFIX + "bkl")) {
            if (body.visible && body.hasArea() && body.top >= list.top && body.bottom <= list.bottom
                    && text.equals(body.text)) count++;
        }
        return count;
    }

    static UiNode conversationList(UiNode root) {
        List<UiNode> exact = root.find(PREFIX + "j8g");
        if (!exact.isEmpty()) return uniqueVisible(exact);
        List<UiNode> candidates = new ArrayList<>();
        collectConversationLists(root, candidates);
        return uniqueVisible(candidates);
    }

    private static void collectConversationLists(UiNode node, List<UiNode> lists) {
        if (node.visible && node.type.endsWith("ListView") && !conversationLabels(node).isEmpty()) lists.add(node);
        for (UiNode child : node.children) collectConversationLists(child, lists);
    }

    static String diagnosis(UiNode root) {
        return "列表 " + root.find(PREFIX + "j8g").size() + "，会话 " + root.find(PREFIX + "kbq").size()
                + "，聊天区 " + root.find(PREFIX + "bp0").size() + "，输入框 " + root.find(PREFIX + "bkk").size()
                + "，列表控件 " + listTypes(root, new ArrayList<>());
    }

    static List<String> listTypes(UiNode node, List<String> result) {
        if (result.size() >= 4) return result;
        if (node.visible && (node.type.endsWith("ListView") || node.type.endsWith("RecyclerView"))) {
            result.add(node.id.isEmpty() ? node.type : node.id.substring(node.id.lastIndexOf('/') + 1));
        }
        for (UiNode child : node.children) listTypes(child, result);
        return result;
    }

    static List<UiNode> conversationLabels(UiNode list) {
        List<UiNode> labels = new ArrayList<>();
        for (UiNode node : list.find(PREFIX + "kbq")) {
            if (node.visible && node.hasArea() && !label(node).isEmpty()) labels.add(node);
        }
        return labels;
    }

    static String label(UiNode node) { return (node.text.isEmpty() ? node.description : node.text).trim(); }

}
