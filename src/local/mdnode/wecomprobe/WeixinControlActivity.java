package local.mdnode.wecomprobe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

public final class WeixinControlActivity extends Activity {
    static final String SAMPLE = "APK 节点对照测试";
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView sample = new TextView(this);
        sample.setText(SAMPLE);
        sample.setTextSize(24);
        sample.setGravity(android.view.Gravity.CENTER);
        setContentView(sample);
        handler.postDelayed(() -> {
            Intent weixin = getPackageManager().getLaunchIntentForPackage(WeixinLayout.PACKAGE);
            if (weixin != null) startActivity(weixin);
            finish();
        }, 3400);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
