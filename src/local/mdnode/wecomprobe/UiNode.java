package local.mdnode.wecomprobe;

import java.util.ArrayList;
import java.util.List;

/** Plain data copied from accessibility nodes; contains no Android handles. */
final class UiNode {
    String id = "", type = "", text = "", description = "";
    int left, top, right, bottom;
    boolean visible = true, editable, clickable, enabled, hint;
    boolean longClickable, scrollable, focused, selected;
    int rowIndex = -1;
    final List<UiNode> children = new ArrayList<>();

    boolean hasArea() { return right > left && bottom > top; }

    List<UiNode> find(String resourceId) {
        List<UiNode> result = new ArrayList<>();
        collect(resourceId, result);
        return result;
    }

    private void collect(String resourceId, List<UiNode> result) {
        if (resourceId.equals(id)) result.add(this);
        for (UiNode child : children) child.collect(resourceId, result);
    }
}
