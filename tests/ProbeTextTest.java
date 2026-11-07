package local.mdnode.wecomprobe;

public final class ProbeTextTest {
    public static void main(String[] args) {
        String group = "外部测试群";
        require(ProbeText.isGroupTitle(group, group), "exact group title");
        require(ProbeText.isGroupTitle(group + " (12)", group), "ASCII member count");
        require(ProbeText.isGroupTitle(group + "（12）", group), "fullwidth member count");
        require(ProbeText.isGroupTitle(group + "（演练）", group + "（演练）"), "actual parentheses in title");
        require(!ProbeText.isGroupTitle(group + "二组", group), "similarly named group");
        require(!ProbeText.isGroupTitle(group + "（演练）", group), "non-numeric suffix");

        String marker = "MDTEST-a1b2c3d4";
        require(ProbeText.containsMarker(marker, marker), "standalone marker");
        require(ProbeText.containsMarker("测试成员：" + marker + "，刚刚", marker), "combined accessible label");
        require(!ProbeText.containsMarker(marker + "5", marker), "longer marker with same prefix");
        require(!ProbeText.containsMarker("other_" + marker, marker), "embedded marker");
        require(!ProbeText.containsMarker(null, marker), "missing node text");
        require(ProbeText.containsMarker(marker + "0；" + marker, marker), "valid marker after partial match");
        System.out.println("Probe text tests passed");
    }

    private static void require(boolean condition, String scenario) {
        if (!condition) {
            throw new AssertionError(scenario);
        }
    }
}
