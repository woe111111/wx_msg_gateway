package local.mdnode.wecomprobe;

final class ProbeText {
    private ProbeText() {
    }

    static boolean isGroupTitle(CharSequence value, String group) {
        if (value == null) {
            return false;
        }
        String title = value.toString().trim();
        if (title.equals(group)) {
            return true;
        }
        if (!title.startsWith(group)) {
            return false;
        }
        String suffix = title.substring(group.length()).trim();
        return suffix.matches("\\([0-9]+\\)|（[0-9]+）");
    }

    static boolean containsMarker(CharSequence value, String marker) {
        if (value == null || marker.isEmpty()) {
            return false;
        }
        String text = value.toString();
        for (int start = text.indexOf(marker); start >= 0; start = text.indexOf(marker, start + 1)) {
            int end = start + marker.length();
            if ((start == 0 || !isMarkerCharacter(text.charAt(start - 1)))
                    && (end == text.length() || !isMarkerCharacter(text.charAt(end)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMarkerCharacter(char value) {
        return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9') || value == '-' || value == '_';
    }
}
