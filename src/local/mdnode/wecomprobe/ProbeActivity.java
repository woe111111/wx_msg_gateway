package local.mdnode.wecomprobe;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.AtomicFile;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ProbeActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SharedPreferences state;
    private EditText groups;
    private TextView status, records;
    private long shownSavedAt = -1;
    private boolean resumed;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            renderStatus();
            long savedAt = state.getLong("last_saved_ms", 0);
            if (savedAt != shownSavedAt) { shownSavedAt = savedAt; loadRecords(); }
            if (resumed) handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        state = getSharedPreferences("probe", MODE_PRIVATE);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        int gap = (int)(20 * getResources().getDisplayMetrics().density);
        page.setPadding(gap, gap, gap, gap);
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                page.setPadding(gap+bars.left, gap+bars.top, gap+bars.right, gap+bars.bottom);
            }
            return insets;
        });
        TextView instructions = new TextView(this);
        instructions.setText("只读普通文本。填写群名名单后开始读取，手动打开其中一个群并保持亮屏。当前版不自动切群、不发消息、不上传。记录基于界面观察，可能不完整或重复。");
        page.addView(instructions);
        button(page, "打开无障碍设置", () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        groups = new EditText(this); groups.setHint("允许读取的完整群名，每行一个"); groups.setMinLines(2); groups.setMaxLines(6);
        groups.setText(state.getString("groups", state.getString("group", ""))); page.addView(groups);
        button(page, "开始读取", this::startReading);
        button(page, "停止读取", () -> {
            state.edit().putBoolean("enabled", false).putString("status", "STOPPED").apply(); renderStatus();
        });
        status = new TextView(this); status.setTextIsSelectable(true); page.addView(status);
        button(page, "查看采集字段", this::showFields);
        button(page, "刷新本地记录", () -> { renderStatus(); loadRecords(); });
        button(page, "导出 JSON", () -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json").putExtra(Intent.EXTRA_TITLE, "wecom-text-records.json");
            try { startActivityForResult(intent, 7); }
            catch (RuntimeException error) { toast("系统文件选择器不可用"); }
        });
        records = new TextView(this); records.setTextIsSelectable(true); page.addView(records);
        ScrollView scroll = new ScrollView(this); scroll.addView(page); setContentView(scroll);
    }
    private void button(LinearLayout page, String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setOnClickListener(view -> action.run()); page.addView(button);
    }
    private void startReading() {
        try { ReaderConfig.groups(groups.getText().toString()); }
        catch (IllegalArgumentException error) { groups.setError(error.getMessage()); return; }
        state.edit().putString("groups", groups.getText().toString()).putString("session", UUID.randomUUID().toString())
                .putBoolean("enabled", true).putString("status", "WAITING_FOR_WECOM")
                .putInt("visible_count", 0).putInt("skipped_count", 0).putBoolean("identity_ambiguous", false)
                .remove("skip_details").remove("sender_details").remove("field_dump")
                .putLong("last_scan_ms", 0).putLong("last_saved_ms", 0).remove("marker").apply();
        renderStatus(); shownSavedAt = -1;
    }
    @Override protected void onResume() { super.onResume(); resumed = true; handler.post(refresh); }
    @Override protected void onPause() { resumed = false; handler.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); worker.shutdown(); super.onDestroy(); }
    private boolean serviceEnabled() {
        AccessibilityManager manager = getSystemService(AccessibilityManager.class);
        ComponentName probe = new ComponentName(this, ProbeService.class);
        for (AccessibilityServiceInfo service : manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (probe.equals(ComponentName.unflattenFromString(service.getId()))) return true;
        }
        return false;
    }
    private void renderStatus() {
        boolean enabled = state.getBoolean("enabled", false);
        String phase = !enabled ? "STOPPED" : !serviceEnabled() ? "WAITING_FOR_ACCESSIBILITY" : state.getString("status", "WAITING_FOR_WECOM");
        String details = state.getString("skip_details", "");
        String senders = state.getString("sender_details", "");
        status.setText("探针 0.7\n状态：" + ProbeStatus.label(phase) + "\n本次可见文本：" + state.getInt("visible_count", 0)
                + "，跳过：" + state.getInt("skipped_count", 0) + "\n最后扫描：" + time(state.getLong("last_scan_ms", 0))
                + "\n最后保存：" + time(state.getLong("last_saved_ms", 0))
                + "\n消息身份：界面序列推断" + (state.getBoolean("identity_ambiguous", false) ? "，本次存在不确定匹配" : "")
                + (details.isEmpty() ? "" : "\n\n最近扫描的跳过原因\n" + details)
                + (senders.isEmpty() ? "" : "\n\n发送人诊断\n" + senders));
    }
    private void showFields() {
        String fields = state.getString("field_dump", "等待一次已核对的群扫描");
        TextView content = new TextView(this);
        content.setText(fields);
        content.setTextIsSelectable(true);
        content.setTextSize(13);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        new android.app.AlertDialog.Builder(this).setTitle("最近一次采集字段").setView(scroll)
                .setNeutralButton("复制", (dialog, which) -> {
                    android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("企微采集字段", fields));
                }).setPositiveButton("关闭", null).show();
    }
    private static String time(long value) { return value == 0 ? "无" : DateFormat.getDateTimeInstance().format(new Date(value)); }
    private void loadRecords() {
        worker.execute(() -> {
            try {
                JSONObject data = MessageStore.get(this).export();
                AtomicFile file = new AtomicFile(new File(getFilesDir(), "reader-export.json"));
                FileOutputStream output = file.startWrite();
                try { output.write(data.toString(2).getBytes(StandardCharsets.UTF_8)); file.finishWrite(output); }
                catch (Exception error) { file.failWrite(output); throw error; }
                JSONArray rows = data.getJSONArray("records");
                StringBuilder summary = new StringBuilder("本地共 " + rows.length() + " 条，显示最近 5 条\n");
                for (int i=Math.max(0, rows.length()-5); i<rows.length(); i++) {
                    JSONObject row = rows.getJSONObject(i);
                    summary.append("\n群：").append(row.getString("group_name"))
                            .append("\n发送人：").append(row.isNull("sender_name")
                                    ? ("outgoing".equals(row.getString("direction")) ? "本机账号" : "未识别")
                                    : row.getString("sender_name"))
                            .append(" / ").append(row.getString("direction"))
                            .append("\n").append(row.getString("text"))
                            .append("\n").append(row.getString("observation_kind"))
                            .append(" / ").append(row.getString("identity_quality")).append("\n");
                }
                runOnUiThread(() -> { if (!isDestroyed()) records.setText(summary.toString()); });
            } catch (Exception error) {
                runOnUiThread(() -> { if (!isDestroyed()) records.setText("读取本地记录失败：" + error.getClass().getSimpleName()); });
            }
        });
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 7 || result != RESULT_OK || data == null || data.getData() == null) return;
        final android.net.Uri uri = data.getData();
        worker.execute(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new java.io.IOException("No output");
                output.write(MessageStore.get(this).export().toString(2).getBytes(StandardCharsets.UTF_8));
                runOnUiThread(() -> toast("已导出本地记录"));
            } catch (Exception error) { runOnUiThread(() -> toast("导出失败")); }
        });
    }
    private void toast(String value) { if (!isDestroyed()) Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
}
