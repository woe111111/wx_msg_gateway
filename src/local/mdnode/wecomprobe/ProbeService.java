package local.mdnode.wecomprobe;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ProbeService extends AccessibilityService {
    private static final String TAG = "WecomReadProbe", PACKAGE = "com.tencent.wework";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();
    private SharedPreferences state;
    private volatile long generation;
    private volatile boolean destroyed;
    private long wecomVersion;
    private final Runnable eventScan = this::scanWindow;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!enabled()) return;
            scanWindow();
            handler.postDelayed(this, 1000);
        }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener configChanged = (prefs, key) -> {
        if ("enabled".equals(key) || "session".equals(key) || "groups".equals(key)) handler.post(this::restart);
    };

    @Override protected void onServiceConnected() {
        if (state == null) {
            state = getSharedPreferences("probe", MODE_PRIVATE);
            state.registerOnSharedPreferenceChangeListener(configChanged);
        }
        wecomVersion = currentVersion();
        Log.i(TAG, "SERVICE_CONNECTED");
        restart();
    }

    private long currentVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(PACKAGE, 0);
            return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception ignored) { return -1; }
    }

    private boolean enabled() { return !destroyed && state != null && state.getBoolean("enabled", false); }
    private void restart() {
        generation++;
        handler.removeCallbacks(poll); handler.removeCallbacks(eventScan);
        if (enabled()) handler.post(poll); else status("STOPPED");
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (enabled()) {
            handler.removeCallbacks(eventScan);
            handler.postDelayed(eventScan, 200);
        }
    }
    @Override public void onInterrupt() { generation++; status("ERROR_INTERRUPTED"); }
    @Override public void onDestroy() {
        destroyed = true; generation++;
        handler.removeCallbacksAndMessages(null);
        if (state != null) state.unregisterOnSharedPreferenceChangeListener(configChanged);
        worker.shutdown();
        super.onDestroy();
    }

    private void scanWindow() {
        if (!enabled() || busy.get()) return;
        PowerManager power = getSystemService(PowerManager.class);
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if (!power.isInteractive() || keyguard.isKeyguardLocked()) { generation++; status("LOCKED"); return; }
        List<String> groups;
        try { groups = ReaderConfig.groups(state.getString("groups", "")); }
        catch (IllegalArgumentException error) { status("ERROR_CONFIG"); return; }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) { generation++; status("WAITING_FOR_WECOM"); return; }
        UiNode snapshot;
        try {
            if (!PACKAGE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
                generation++; status("WAITING_FOR_WECOM"); return;
            }
            wecomVersion = currentVersion();
            if (wecomVersion != 84802) { generation++; status("UNSUPPORTED_LAYOUT"); return; }
            if (!approvedWindow(root, groups)) { generation++; status("WRONG_GROUP"); return; }
            snapshot = copy(root, 0, new int[]{0});
        } catch (RuntimeException error) { generation++; status("ERROR_SNAPSHOT"); return; }
        finally { root.recycle(); }
        final String session = state.getString("session", "");
        if (session.isEmpty()) { status("ERROR_CONFIG"); return; }
        final long token = ++generation, observedAt = System.currentTimeMillis(), version = wecomVersion;
        busy.set(true);
        worker.execute(() -> {
            try {
                MessageExtractor.Result scan = MessageExtractor.extract(snapshot, groups, version);
                if (!enabled() || generation != token || !session.equals(state.getString("session", ""))) return;
                if (!"READING".equals(scan.status)) { status(scan.status); return; }
                String fields = MessageFieldDump.render(snapshot, scan);
                MessageTracker.Result saved = MessageStore.get(this).save(session, groups, scan, observedAt);
                if (!enabled() || generation != token) return;
                SharedPreferences.Editor edit = state.edit().putLong("last_scan_ms", observedAt)
                        .putInt("visible_count", scan.messages.size()).putInt("skipped_count", scan.skipped)
                        .putString("skip_details", android.text.TextUtils.join("\n", scan.skipDetails))
                        .putString("sender_details", android.text.TextUtils.join("\n", scan.senderDetails))
                        .putBoolean("identity_ambiguous", saved.ambiguous);
                if (!fields.equals(state.getString("field_dump", ""))) edit.putString("field_dump", fields);
                if (!saved.added.isEmpty()) {
                    edit.putLong("last_saved_ms", observedAt);
                    Log.i(TAG, "RECORDS_ADDED count=" + saved.added.size());
                }
                edit.apply(); status("READING");
            } catch (MessageStore.Full full) { status("STORAGE_FULL"); }
            catch (Exception error) { status("ERROR_STORAGE"); }
            finally { busy.set(false); }
        });
    }

    private static UiNode copy(AccessibilityNodeInfo source, int depth, int[] count) {
        if (depth > 64 || ++count[0] > 2500) throw new IllegalStateException("Snapshot limit");
        UiNode node = new UiNode();
        node.id = value(source.getViewIdResourceName()); node.type = value(source.getClassName());
        node.text = value(source.getText()); node.description = value(source.getContentDescription());
        if (node.text.length() > 65536) throw new IllegalStateException("Text limit");
        node.visible = source.isVisibleToUser(); node.editable = source.isEditable();
        node.enabled = source.isEnabled(); node.clickable = source.isClickable();
        node.longClickable = source.isLongClickable(); node.scrollable = source.isScrollable();
        node.focused = source.isFocused(); node.selected = source.isSelected();
        node.hint = Build.VERSION.SDK_INT >= 26 && source.isShowingHintText();
        AccessibilityNodeInfo.CollectionItemInfo item = source.getCollectionItemInfo();
        if (item != null) node.rowIndex = item.getRowIndex();
        Rect bounds = new Rect(); source.getBoundsInScreen(bounds);
        node.left=bounds.left; node.top=bounds.top; node.right=bounds.right; node.bottom=bounds.bottom;
        for (int i=0; i<source.getChildCount(); i++) {
            AccessibilityNodeInfo child = source.getChild(i);
            if (child == null) throw new IllegalStateException("Incomplete tree");
            try { node.children.add(copy(child, depth+1, count)); } finally { child.recycle(); }
        }
        return node;
    }
    private static boolean approvedWindow(AccessibilityNodeInfo root, List<String> groups) {
        Rect window = new Rect(), bounds = new Rect(); root.getBoundsInScreen(window);
        int matches = 0;
        for (String group : groups) {
            for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(group)) {
                try {
                    node.getBoundsInScreen(bounds);
                    if (node.isVisibleToUser() && !node.isEditable() && !bounds.isEmpty()
                            && bounds.bottom <= window.top + window.height()/5
                            && "android.widget.TextView".contentEquals(node.getClassName() == null ? "" : node.getClassName())
                            && ProbeText.isGroupTitle(node.getText(), group)) matches++;
                } finally { node.recycle(); }
            }
        }
        return matches == 1;
    }
    private static String value(CharSequence value) { return value == null ? "" : value.toString(); }
    private void status(String phase) {
        if (state == null || destroyed) return;
        if (!state.getBoolean("enabled", false) && !"STOPPED".equals(phase)) return;
        if (!phase.equals(state.getString("status", ""))) {
            state.edit().putString("status", phase).apply(); Log.i(TAG, phase);
        }
    }
}
