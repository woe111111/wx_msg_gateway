package local.mdnode.wecomprobe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

final class ReaderConfig {
    static List<String> groups(String text) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String line : text.replace("\r", "").split("\n")) {
            String name = line.trim();
            if (!name.isEmpty()) names.add(name);
        }
        if (names.isEmpty() || names.size() > 50) throw new IllegalArgumentException("请填写 1 至 50 个群名，每行一个");
        for (String name : names) if (name.length() > 100) throw new IllegalArgumentException("群名不能超过 100 字符");
        return new ArrayList<>(names);
    }
}
