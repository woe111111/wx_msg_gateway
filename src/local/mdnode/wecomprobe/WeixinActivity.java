package local.mdnode.wecomprobe;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.CheckBox;
import android.text.InputType;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class WeixinActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences state;
    private TextView status;
    private EditText ip, port, client, registrationKey;
    private CheckBox https;
    private boolean waitingToOpen;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 500);
        }
    };

    boolean isWeCom() { return false; }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        state = getSharedPreferences(WeixinService.PREFS, MODE_PRIVATE);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        page.setPadding(pad, pad, pad, pad);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            page.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom);
                return insets;
            });
        }
        TextView intro = new TextView(this);
        String app = isWeCom() ? "企业微信" : "微信";
        intro.setText(app + (isWeCom() ? " 5.0.13" : " 8.0.80")
                + " · 本地网页收发\n保持会话列表亮屏前台。应用会遍历会话列表并同步群聊，网页上的发送任务由手机执行。");
        page.addView(intro);
        if (isWeCom()) {
            ip = field(page, "服务器 IP", state.getString("server_ip", ""), InputType.TYPE_CLASS_TEXT);
            port = field(page, "服务器端口", state.getString("server_port", ""), InputType.TYPE_CLASS_NUMBER);
            client = field(page, "Client ID", state.getString("client_id", ""), InputType.TYPE_CLASS_TEXT);
            registrationKey = field(page, "注册 Key", state.getString("registration_key", ""),
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            https = new CheckBox(this);
            https.setText("使用 HTTPS");
            https.setChecked(state.getBoolean("server_https", true));
            page.addView(https);
        }
        button(page, "打开无障碍设置", () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        button(page, "连接网页并打开" + app, () -> {
            WeixinService service = requireService();
            if (service == null) return;
            if (isWeCom()) {
                try {
                    String address = ip.getText().toString().trim(), portValue = port.getText().toString().trim();
                    String clientId = client.getText().toString().trim(), key = registrationKey.getText().toString().trim();
                    DeviceGateway gateway = new DeviceGateway(address, Integer.parseInt(portValue), clientId, key, https.isChecked());
                    if (!service.configure(gateway)) {
                        Toast.makeText(this, "当前任务尚未完成，请稍后再切换设备配置", Toast.LENGTH_LONG).show();
                        return;
                    }
                    state.edit().putString("server_ip", address).putString("server_port", portValue)
                            .putString("client_id", clientId).putString("registration_key", key)
                            .putBoolean("server_https", https.isChecked()).apply();
                    waitingToOpen = service.start();
                } catch (IllegalArgumentException invalid) {
                    Toast.makeText(this, invalid instanceof NumberFormatException ? "请输入有效的端口" : invalid.getMessage(),
                            Toast.LENGTH_LONG).show();
                }
            } else if (service.start()) openWeixin();
            else Toast.makeText(this, "缺少网页配对配置", Toast.LENGTH_LONG).show();
        });
        if (isWeCom()) button(page, "只读同步群成员", () -> {
            WeixinService service = requireService();
            if (service == null) return;
            if (service.startMemberCollection()) {
                waitingToOpen = true;
                Toast.makeText(this, "进入已同步的群，手动输入 @ 打开选人页", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "请先保存连接，或等当前请求完成后再试", Toast.LENGTH_LONG).show();
            }
        });
        if (!isWeCom()) button(page, "运行只读诊断", () -> {
            WeixinService service = requireService();
            if (service != null && service.startDiagnostic()) openWeixin();
        });
        button(page, "停止测试", () -> {
            WeixinService service = WeixinService.current();
            if (service != null) service.stop();
            waitingToOpen = false;
            render();
        });
        status = new TextView(this);
        status.setTextIsSelectable(true);
        page.addView(status);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(page);
        setContentView(scroll);
    }

    private EditText field(LinearLayout page, String label, String value, int inputType) {
        TextView caption = new TextView(this);
        EditText input = new EditText(this);
        input.setId(android.view.View.generateViewId());
        input.setSingleLine(true);
        input.setInputType(inputType);
        input.setText(value);
        caption.setText(label);
        caption.setLabelFor(input.getId());
        page.addView(caption);
        page.addView(input);
        return input;
    }

    private void button(LinearLayout page, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        page.addView(button);
    }

    private void openWeixin() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(isWeCom() ? WeComLayout.PACKAGE : WeixinLayout.PACKAGE);
        if (intent != null) startActivity(intent);
        else Toast.makeText(this, "请手动打开目标应用的会话列表", Toast.LENGTH_SHORT).show();
    }

    private WeixinService requireService() {
        WeixinService service = WeixinService.current();
        if (service == null) Toast.makeText(this, "请先开启本应用的无障碍服务", Toast.LENGTH_LONG).show();
        return service;
    }

    private void render() {
        WeixinService service = WeixinService.current();
        if (waitingToOpen && service != null && service.approvedConnection()) {
            waitingToOpen = false;
            openWeixin();
        }
        status.setText((WeixinService.current() == null ? "无障碍未连接" : "无障碍已连接")
                + "\n\n" + state.getString("status", "等待启动")
                + "\n\n最近诊断\n" + state.getString("diagnostic_log", ""));
    }

    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}
