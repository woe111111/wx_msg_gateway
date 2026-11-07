package local.mdnode.wecomprobe;

import java.util.Arrays;

public final class WeComMentionPickerTest {
    private static UiNode node(String id, String text) {
        UiNode node = new UiNode();
        node.id = MessageExtractor.PREFIX + id;
        node.text = text;
        node.right = node.bottom = 100;
        node.enabled = true;
        return node;
    }

    private static UiNode row(UiNode list, String name, String count) {
        UiNode row = node("lqg", "");
        row.clickable = true;
        row.children.add(node("hlo", name));
        if (count != null) row.children.add(node("rightTxt", count));
        list.children.add(row);
        return row;
    }

    private static void check(boolean result) {
        if (!result) throw new AssertionError("Unexpected picker result");
    }

    public static void main(String[] args) {
        UiNode root = node("ly0", "");
        root.children.add(node("o6j", "选择提醒的人"));
        root.children.add(node("ei1", "搜索"));
        UiNode list = node("l_8", "");
        root.children.add(list);
        row(list, "所有人", "(3)");
        UiNode a = row(list, "测试甲", null);
        UiNode b = row(list, "测试乙", null);
        WeComMentionPicker picker = WeComMentionPicker.identify(root, "测试群(3)");
        check(picker != null && picker.member("测试甲") == a && picker.member("所有人") == null);
        check(picker.matchesRoster(Arrays.asList("测试乙", "测试甲")));
        check(!picker.matchesRoster(Arrays.asList("测试甲", "测试丙")));
        check(WeComMentionPicker.identify(root, "其他群(2)") == null);
        list.scrollable = true;
        check(WeComMentionPicker.identify(root, "测试群(3)") == null);
        list.scrollable = false;
        b.children.get(0).text = "测试甲";
        picker = WeComMentionPicker.identify(root, "测试群(3)");
        check(picker != null && picker.member("测试甲") == null);
        String body = "测试正文";
        check(WeComMentionPicker.draftBeforeMention(body).equals(body + "   "));
        check(!WeComMentionPicker.matchesSelection(body, "测试甲", body + "   @"));
        check(WeComMentionPicker.matchesSelection(body, "测试甲", body + "   @测试甲\u2005"));
        check(!WeComMentionPicker.matchesSelection(body, "测试甲", body + "   @测试甲乙"));
        check(!WeComMentionPicker.matchesSelection(body, "测试甲", body + "  @测试甲"));
        check(!WeComMentionPicker.matchesSelection(body, "测试甲", body + "   @所有人"));
        check(!WeComMentionPicker.matchesSelection(body, "测试甲", "@所有人\n@所有人"));
        check(!WeComMentionPicker.matchesSelection(body, "测试甲", "@测试甲\n" + body));
        System.out.println("WeCom native mention picker checks passed");
    }
}
