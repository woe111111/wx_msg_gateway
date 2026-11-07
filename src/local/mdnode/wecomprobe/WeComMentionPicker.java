package local.mdnode.wecomprobe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Selectors observed in WeCom 84802. Require the complete roster before matching a name. */
final class WeComMentionPicker {
    private static final String PREFIX = MessageExtractor.PREFIX;
    final List<String> names;
    private final Map<String, UiNode> uniqueRows;

    private WeComMentionPicker(List<String> names, Map<String, UiNode> uniqueRows) {
        this.names = names;
        this.uniqueRows = uniqueRows;
    }

    static WeComMentionPicker identify(UiNode root, String groupTitle) {
        UiNode title = only(root, "o6j");
        UiNode list = only(root, "l_8");
        UiNode search = only(root, "ei1");
        if (title == null || !"选择提醒的人".equals(title.text)
                || list == null || list.scrollable || search == null
                || !("搜索".equals(search.text) || search.text.isEmpty() && search.hint)) return null;
        groupTitle = groupTitle.replace('（', '(').replace('）', ')');
        int split = groupTitle.lastIndexOf('(');
        if (split < 0 || !groupTitle.endsWith(")")) return null;
        String count = groupTitle.substring(split).replace(" ", "");
        if (!count.matches("\\([1-9][0-9]{0,2}\\)")) return null;

        boolean sawAll = false;
        List<String> names = new ArrayList<>();
        Map<String, UiNode> uniqueRows = new LinkedHashMap<>();
        for (UiNode row : list.find(PREFIX + "lqg")) {
            if (!row.visible || !row.hasArea() || row.top < list.top || row.bottom > list.bottom
                    || !row.clickable || !row.enabled) return null;
            UiNode label = only(row, "hlo");
            if (label == null || label.text.trim().isEmpty()) return null;
            String name = label.text;
            if (name.equals("所有人")) {
                UiNode memberCount = only(row, "rightTxt");
                if (sawAll || memberCount == null || !count.equals(memberCount.text.replace(" ", ""))) return null;
                sawAll = true;
            } else {
                if (name.length() > 120 || !name.equals(name.trim()) || name.contains("\n")) return null;
                if (names.contains(name)) uniqueRows.remove(name);
                else uniqueRows.put(name, row);
                names.add(name);
            }
        }
        int total = Integer.parseInt(count.substring(1, count.length() - 1));
        return sawAll && names.size() == total - 1 ? new WeComMentionPicker(names, uniqueRows) : null;
    }

    static boolean isPicker(UiNode root) {
        UiNode title = only(root, "o6j");
        return title != null && "选择提醒的人".equals(title.text);
    }

    boolean matchesRoster(List<String> expected) {
        List<String> actual = new ArrayList<>(names);
        List<String> sorted = new ArrayList<>(expected);
        actual.sort(String::compareTo);
        sorted.sort(String::compareTo);
        return actual.equals(sorted);
    }

    UiNode member(String name) { return uniqueRows.get(name); }

    static String draftBeforeMention(String body) {
        return body + "   ";
    }

    static boolean matchesSelection(String body, String name, String draft) {
        String expected = draftBeforeMention(body) + "@" + name;
        return draft.startsWith(expected)
                && draft.substring(expected.length()).matches("[ \u00a0\u2005\u202f\u3000]*");
    }

    private static UiNode only(UiNode parent, String id) {
        return ChatPage.uniqueVisible(parent.find(PREFIX + id));
    }
}
