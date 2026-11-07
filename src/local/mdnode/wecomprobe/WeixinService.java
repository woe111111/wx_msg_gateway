package local.mdnode.wecomprobe;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WeixinService extends AccessibilityService {
    static final String PREFS = "weixin_test";
    private static WeixinService connected;
    private static final int MAX_CAPTURES = 32;
    private static final int MAX_FORWARD_WINDOWS = 120;
    private static final int BACKWARD = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
    private static final int FORWARD = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Runnable tick = this::scan;
    private final Map<String, Integer> counts = new HashMap<>();
    private final Set<String> visited = new HashSet<>();
    private final Set<String> unopened = new HashSet<>();
    private Map<String, String> verifiedRoutes = new HashMap<>();
    private final Map<String, String> foundRoutes = new HashMap<>();
    private final Map<String, String> groupPreviews = new HashMap<>();
    private final Map<String, Long> groupGapRetries = new HashMap<>();
    private final Map<String, String> lastRows = new HashMap<>();
    private SharedPreferences state, nonGroupRows, buffers;
    private DeviceGateway gateway;
    private JSONObject deviceConfig, memberSnapshot;
    private boolean collectingMembers, pickerSelected;
    private long memberChatSeen;
    private String memberChatName, memberChatTitle, submittedBody;
    private String reservationFor;
    private long reservationUntil, jobLeaseUntil;
    private boolean mayClick;
    private String historySweep, historyPrevious;
    private JSONArray historyJoined;
    private boolean historyRetry;
    private JSONArray spool = new JSONArray();
    private final List<JSONObject> events = new ArrayList<>();
    private JSONObject completion, routes = new JSONObject(), job;
    private String endpoint, token, status = "尚未连接";
    private boolean running, syncing, online, routesChanged, foregroundList, retriedOpen;
    private long nextScan, nextSync, deadline, idleUntil, generation, openUntil;
    private Phase phase = Phase.TOP_DISCOVER;
    private List<String> waitingViewport;
    private int stuck, scrolls, chatScrolls, baseline, sendWindow, unchangedGroups;
    private String opening, openingSignature, openingPreview, openingMentionPreview, chatSnapshot, lastVerifyTrace;
    private String pausedGroup, pausedTitle, resumeRequest, resumeAck;
    private boolean resumeOpened;
    private long resumeDeadline;
    private final WeComSendRecovery sendRecovery = new WeComSendRecovery();
    private boolean recoveryCanDismissPicker;
    private String windowClass = "", windowDetails = "";
    private boolean diagnosing, controlTried, controlRead, guideReported;
    private long diagnosticUntil, diagnosticStarted;
    private long lastContentProbe;
    private long controlUntil;
    private final Set<String> probeResults = new HashSet<>();

    private enum Phase { TOP_DISCOVER, DISCOVER, TOP_READ, READ, OPENING, CHAT, BACK, PREPARE_MENTION, PICK_MENTION, FILL, RECOVER_SEND, VERIFY, IDLE }

    boolean isWeCom() { return false; }

    private String targetName() { return isWeCom() ? "企业微信" : "微信"; }

    private String targetPackage() { return isWeCom() ? WeComLayout.PACKAGE : WeixinLayout.PACKAGE; }

    static WeixinService current() { return connected; }

    @Override protected void onServiceConnected() {
        state = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (isWeCom()) nonGroupRows = getSharedPreferences("non_group_" + WeComLayout.VERSION, MODE_PRIVATE);
        connected = this;
        AccessibilityServiceInfo serviceInfo = getServiceInfo();
        if (serviceInfo != null) {
            serviceInfo.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            setServiceInfo(serviceInfo);
            trace("无障碍服务已连接，能力 " + serviceInfo.getCapabilities() + "，标记 " + serviceInfo.flags);
        }
        buffers = state;
        if (isWeCom() && state.contains("client_id")) {
            try {
                DeviceGateway saved = new DeviceGateway(state.getString("server_ip", ""),
                        Integer.parseInt(state.getString("server_port", "")), state.getString("client_id", ""),
                        state.getString("registration_key", ""), state.getBoolean("server_https", true));
                configure(saved);
            } catch (IllegalArgumentException invalid) {
                display("服务器配置无效");
            }
        } else if (!isWeCom()) {
            loadBuffers();
            try (InputStream input = getAssets().open("gateway.json")) {
                JSONObject config = new JSONObject(readString(input, 4096));
                endpoint = config.getString("url") + "/device/sync";
                token = config.getString("token");
            } catch (Exception error) { display("请配置服务器和注册 Key"); }
        }
        if (isWeCom() && (state.getBoolean("resume_scanning", false)
                || "等待界面超时".equals(state.getString("status", "")))) start();
        if (!isWeCom() && endpoint != null && !state.getBoolean("diagnostic_v8_started", false)) {
            state.edit().putBoolean("diagnostic_v8_started", true).apply();
            startDiagnostic();
        }
    }

    private void loadBuffers() {
        spool = new JSONArray();
        completion = null;
        try {
            spool = new JSONArray(buffers.getString("spool", "[]"));
            String result = buffers.getString("completion", "");
            if (!result.isEmpty()) completion = new JSONObject(result);
            String clicked = buffers.getString("click_pending_id", "");
            if (!clicked.isEmpty() && completion == null) {
                completion = result(clicked, "uncertain", "点击后应用重启，请在群内核对");
                if (gateway != null) completion.put("group_id", buffers.getString("click_pending_group", ""))
                        .put("event_id", UUID.randomUUID().toString());
                saveBuffers();
            }
        } catch (JSONException error) { display("本地回传记录无法读取"); }
    }

    boolean configure(DeviceGateway next) {
        if (next.sameCredentials(gateway)) return true;
        if (job != null) return false;
        pause();
        gateway = next;
        buffers = getSharedPreferences("gateway_v1_" + UUID.nameUUIDFromBytes(
                (next.base + "\n" + next.clientId).getBytes(StandardCharsets.UTF_8)), MODE_PRIVATE);
        nonGroupRows = getSharedPreferences("gateway_non_group_" + UUID.nameUUIDFromBytes(
                (next.base + "\n" + next.clientId).getBytes(StandardCharsets.UTF_8)), MODE_PRIVATE);
        verifiedRoutes.clear();
        groupPreviews.clear();
        groupGapRetries.clear();
        lastRows.clear();
        events.clear();
        routes = new JSONObject();
        routesChanged = false;
        deviceConfig = null;
        memberSnapshot = null;
        loadBuffers();
        return true;
    }

    boolean approvedConnection() { return gateway != null && running && online; }

    boolean startDiagnostic() {
        if (isWeCom() || endpoint == null || job != null) return false;
        running = diagnosing = true;
        generation++;
        nextSync = nextScan = 0;
        probeResults.clear();
        lastContentProbe = 0;
        controlTried = controlRead = false;
        diagnosticStarted = SystemClock.elapsedRealtime();
        diagnosticUntil = diagnosticStarted + 75000;
        display("APK 只读诊断已启动，等待微信测试群");
        scheduleDiagnostic();
        return true;
    }

    private void scheduleDiagnostic() {
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    boolean start() {
        if (endpoint == null && gateway == null) return false;
        if (running && !diagnosing && !collectingMembers && pausedGroup == null && idleUntil != Long.MAX_VALUE) return true;
        if (job != null) return true;
        diagnosing = collectingMembers = false;
        pausedGroup = pausedTitle = resumeRequest = null;
        running = true;
        if (isWeCom()) state.edit().putBoolean("resume_scanning", true).apply();
        generation++;
        nextSync = 0;
        resetRound();
        display("正在连接本地网页");
        scheduleDiagnostic();
        return true;
    }

    boolean startMemberCollection() {
        if (!isWeCom() || gateway == null || job != null || syncing || Build.VERSION.SDK_INT != 28) return false;
        stop();
        collectingMembers = running = true;
        memberChatName = null;
        memberChatSeen = 0;
        nextScan = nextSync = 0;
        display("打开已同步的群，清空草稿，再手动输入 @ 打开选人页");
        scheduleDiagnostic();
        return true;
    }

    void stop() {
        if (isWeCom()) state.edit().putBoolean("resume_scanning", false).apply();
        pause();
    }

    private void pause() {
        if (job != null) finishJob(phase == Phase.VERIFY ? "uncertain" : "blocked", "手机已停止");
        running = false;
        foregroundList = false;
        diagnosing = collectingMembers = false;
        generation++;
        online = false;
        handler.removeCallbacks(tick);
        display("手机已停止");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.getClassName() != null) {
            windowClass = event.getClassName().toString();
        }
        if (!running || !diagnosing || SystemClock.elapsedRealtime() >= diagnosticUntil
                || !WeixinLayout.PACKAGE.contentEquals(event.getPackageName() == null ? "" : event.getPackageName())) return;
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            probe("窗口事件节点", event.getSource());
        } else if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastContentProbe >= 800) {
                lastContentProbe = now;
                probe("内容变化事件节点", event.getSource());
            }
        }
    }
    @Override public void onInterrupt() { stop(); }

    @Override public void onDestroy() {
        if (connected == this) connected = null;
        if (state != null) pause();
        network.shutdownNow();
        super.onDestroy();
    }

    private void resetRound() {
        counts.clear();
        visited.clear();
        unopened.clear();
        foundRoutes.clear();
        unchangedGroups = 0;
        waitingViewport = null;
        scrolls = 0;
        nextScan = 0;
        phase = Phase.TOP_DISCOVER;
        idleUntil = 0;
    }

    private void scan() {
        if (!running) return;
        long now = SystemClock.elapsedRealtime();
        if (job != null && (now > deadline || (gateway != null && now >= jobLeaseUntil))) {
            if (isWeCom() && phase == Phase.PICK_MENTION && reservationFor == null && now < jobLeaseUntil) {
                beginSendRecovery(now);
            } else {
                finishJob(phase == Phase.VERIFY ? "uncertain" : "blocked",
                        phase == Phase.PICK_MENTION || phase == Phase.RECOVER_SEND
                                ? "原生选人或草稿核对超时，请检查手机草稿" : "等待界面超时");
                if (idleUntil != Long.MAX_VALUE) resetRound();
            }
        }
        if (now >= nextSync && !syncing) sync();
        if (diagnosing && now >= nextScan) diagnose(now);
        if (!diagnosing && online && now >= nextScan && spool.length() < MAX_CAPTURES) inspect(now);
        if (spool.length() >= MAX_CAPTURES) display("等待网页确认已缓存的消息");
        if (running) handler.postDelayed(tick, 350);
    }

    private void diagnose(long now) {
        nextScan = now + 1800;
        if (now >= diagnosticUntil) {
            if (!status.equals("APK 已从测试群读到正文")) display("APK 只读诊断已结束，请查看运行日志");
            if (events.isEmpty() && spool.length() == 0) running = false;
            return;
        }
        if (!getSystemService(PowerManager.class).isInteractive()
                || getSystemService(KeyguardManager.class).isKeyguardLocked()) {
            display("APK 诊断等待亮屏和解锁");
            return;
        }
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null) {
            try {
                if (getPackageName().contentEquals(active.getPackageName() == null ? "" : active.getPackageName()) && controlUntil != 0) {
                    checkControl(AccessibilityNodeInfo.obtain(active));
                }
                probe("活动窗口", AccessibilityNodeInfo.obtain(active));
                if (active.refresh()) probe("活动窗口刷新后", AccessibilityNodeInfo.obtain(active));
                else probeLog("活动窗口刷新未返回节点");
                if (WeixinLayout.PACKAGE.contentEquals(active.getPackageName() == null ? "" : active.getPackageName())) {
                    probeSelectors(active);
                }
            } finally { active.recycle(); }
        } else probeLog("活动窗口无根节点");

        for (AccessibilityWindowInfo window : getWindows()) {
            try {
                if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && window.isFocused()) {
                    probe("指定微信窗口根", window.getRoot());
                }
            } finally { window.recycle(); }
        }
        if (controlUntil != 0 && now >= controlUntil) {
            if (!controlRead) probeLog("自有测试页未读到对照文字");
            controlUntil = 0;
        }
        if (!controlTried && now - diagnosticStarted >= 3000 && !status.equals("APK 已从测试群读到正文")) {
            showControl();
        }
    }

    private void probeSelectors(AccessibilityNodeInfo active) {
        List<AccessibilityNodeInfo> lists = active.findAccessibilityNodeInfosByViewId(WeixinLayout.PREFIX + "bp0");
        List<AccessibilityNodeInfo> composers = active.findAccessibilityNodeInfosByViewId(WeixinLayout.PREFIX + "bkk");
        List<AccessibilityNodeInfo> titles = active.findAccessibilityNodeInfosByText("测试群");
        List<AccessibilityNodeInfo> conversations = active.findAccessibilityNodeInfosByViewId(WeixinLayout.PREFIX + "j8g");
        try {
            probeLog("直接查询。会话列表 " + conversations.size() + "，聊天列表 " + lists.size()
                    + "，输入框 " + composers.size() + "，测试群文字 " + titles.size());
            if (lists.size() != 1 || composers.size() != 1 || titles.isEmpty()) return;
            int windowId = active.getWindowId();
            if (lists.get(0).getWindowId() != windowId || composers.get(0).getWindowId() != windowId) return;
            try (Frame frame = new Frame()) {
                UiNode root = new UiNode();
                root.children.add(frame.copy(AccessibilityNodeInfo.obtain(lists.get(0)), 0));
                root.children.add(frame.copy(AccessibilityNodeInfo.obtain(composers.get(0)), 0));
                for (AccessibilityNodeInfo title : titles) {
                    if (title.getWindowId() == windowId) root.children.add(frame.copy(AccessibilityNodeInfo.obtain(title), 0));
                }
                captureDiagnostic(WeixinLayout.identify(root));
            }
        } catch (RuntimeException | JSONException error) {
            probeLog("直接查询采集异常 " + error.getClass().getSimpleName());
        } finally {
            for (AccessibilityNodeInfo node : lists) node.recycle();
            for (AccessibilityNodeInfo node : composers) node.recycle();
            for (AccessibilityNodeInfo node : titles) node.recycle();
            for (AccessibilityNodeInfo node : conversations) node.recycle();
        }
    }

    private void probe(String path, AccessibilityNodeInfo node) {
        if (node == null) { probeLog(path + "无节点"); return; }
        if (!WeixinLayout.PACKAGE.contentEquals(node.getPackageName() == null ? "" : node.getPackageName())) {
            node.recycle();
            return;
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        probeLog(path + "，尺寸 " + bounds.width() + "×" + bounds.height() + "，子节点 " + node.getChildCount());
        if (bounds.isEmpty() || node.getChildCount() == 0) { node.recycle(); return; }
        try (Frame frame = new Frame()) {
            UiNode root = frame.copy(node, 0);
            probeLog(path + "。" + WeixinLayout.diagnosis(root));
            captureDiagnostic(WeixinLayout.identify(root));
        } catch (RuntimeException | JSONException error) {
            probeLog(path + "采集异常 " + error.getClass().getSimpleName());
        }
    }

    private void captureDiagnostic(WeixinLayout chat) throws JSONException {
        if (chat != null && "测试群".equals(chat.name) && !chat.messages().isEmpty() && spool.length() < MAX_CAPTURES) {
            capture(chat);
            display("APK 已从测试群读到正文");
        }
    }

    private void probeLog(String text) {
        if (probeResults.size() < 40 && probeResults.add(text)) trace(text);
    }

    private void showControl() {
        controlTried = true;
        controlUntil = SystemClock.elapsedRealtime() + 6500;
        try {
            startActivity(new android.content.Intent(this, WeixinControlActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            probeLog("已打开 APK 自有测试页");
        } catch (RuntimeException error) {
            probeLog("自有测试页启动失败 " + error.getClass().getSimpleName());
        }
    }

    private void checkControl(AccessibilityNodeInfo root) {
        if (root == null) return;
        try {
            if (!getPackageName().contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) return;
            List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(WeixinControlActivity.SAMPLE);
            for (AccessibilityNodeInfo match : matches) {
                if (WeixinControlActivity.SAMPLE.contentEquals(match.getText() == null ? "" : match.getText())) controlRead = true;
                match.recycle();
            }
            if (controlRead) probeLog("自有测试页可读取已知文字");
        } finally { root.recycle(); }
    }

    private void inspect(long now) {
        foregroundList = false;
        if (pausedGroup != null) {
            if (resumeRequest != null) inspectResume(now);
            return;
        }
        if (!getSystemService(PowerManager.class).isInteractive()
                || getSystemService(KeyguardManager.class).isKeyguardLocked()) {
            display("等待手机解锁");
            if (job != null) {
                finishJob(phase == Phase.VERIFY ? "uncertain" : "blocked", "屏幕已锁定");
                if (idleUntil != Long.MAX_VALUE) resetRound();
            }
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        if (!targetPackage().contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
            root.recycle();
            if (collectingMembers) { memberChatName = null; return; }
            if (job != null && (phase == Phase.PREPARE_MENTION || phase == Phase.PICK_MENTION
                    || phase == Phase.FILL || phase == Phase.RECOVER_SEND || phase == Phase.VERIFY)) {
                finishJob(phase == Phase.VERIFY ? "uncertain" : "blocked", "发送期间已离开微信");
                if (idleUntil != Long.MAX_VALUE) resetRound();
            }
            display("等待" + targetName() + "在前台");
            return;
        }
        try (Frame frame = new Frame()) {
            UiNode snapshot = frame.copy(root, 0);
            if (!versionSupported()) { display(isWeCom() ? "需要企业微信 5.0.13（84802）" : "需要微信 8.0.80（3220）"); return; }
            UiNode list = isWeCom() ? WeComLayout.conversationList(snapshot) : WeixinLayout.conversationList(snapshot);
            ChatPage chat = isWeCom() ? WeComLayout.identify(snapshot) : WeixinLayout.identify(snapshot);
            if (collectingMembers) {
                inspectMemberCollection(snapshot, chat, now);
                return;
            }
            if (phase == Phase.RECOVER_SEND) {
                recoverSend(snapshot, chat, now);
                return;
            }
            if (phase == Phase.PICK_MENTION) {
                handleMentionPicker(frame, snapshot, chat, root.getWindowId(), now);
                sendRecovery.readSucceeded();
                return;
            }
            foregroundList = list != null;
            if (phase == Phase.IDLE && now >= idleUntil) resetRound();
            if (phase == Phase.OPENING && list != null) {
                if (now >= openUntil && !retryOpen(frame, list, now)) openFailed();
            } else if (phase == Phase.OPENING && job == null && openingSignature != null && isWeCom()
                    && WeComLayout.confirmedNonGroup(snapshot, opening)) {
                nonGroupRows.edit().putString(opening, openingSignature).apply();
                trace("已标记非群会话，下轮跳过「" + opening + "」");
                back(now);
            } else if (phase == Phase.OPENING || phase == Phase.CHAT || phase == Phase.PREPARE_MENTION || phase == Phase.FILL || phase == Phase.VERIFY) {
                if (isWeCom() && phase == Phase.OPENING && now >= openUntil
                        && (chat == null || !opening.equals(chat.name))) {
                    trace("会话入口与群页未匹配。" + WeComLayout.chatDiagnosis(snapshot, opening));
                }
                handleChat(frame, chat, root.getWindowId(), now);
            } else if (phase != Phase.IDLE && list != null) {
                handleList(frame, list, now);
            } else if (phase != Phase.IDLE && chat != null && phase != Phase.BACK) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                nextScan = now + 550;
            } else if (phase == Phase.BACK && now >= nextScan) {
                display("等待返回" + targetName() + "会话列表");
            } else if (phase != Phase.IDLE) {
                display(targetName() + "页面未识别。" + (isWeCom() ? WeComLayout.diagnosis(snapshot) : WeixinLayout.diagnosis(snapshot)));
                describeWindow(frame, root, snapshot);
            }
            sendRecovery.readSucceeded();
        } catch (RuntimeException | JSONException error) {
            trace("界面读取异常 " + phase + " · " + error.getClass().getSimpleName());
            if (retrySendRead(now)) return;
            if (job != null) finishJob(phase == Phase.VERIFY || reservationFor != null
                    ? "uncertain" : "blocked", "界面结构变化");
            if (pausedGroup == null) display("界面读取失败，正在等待稳定页面");
            nextScan = now + 2000;
        }
    }

    private boolean retrySendRead(long now) {
        if (!isWeCom() || job == null || reservationFor != null) return false;
        if (phase == Phase.RECOVER_SEND) {
            nextScan = now + 450;
            return true;
        }
        if (phase != Phase.PREPARE_MENTION && phase != Phase.PICK_MENTION && phase != Phase.FILL) return false;
        nextScan = now + 450;
        if (sendRecovery.retryRead()) {
            display("发送前页面变化，正在重新读取");
        } else {
            beginSendRecovery(now);
        }
        return true;
    }

    private void beginSendRecovery(long now) {
        recoveryCanDismissPicker = phase == Phase.PICK_MENTION;
        phase = Phase.RECOVER_SEND;
        deadline = Math.min(jobLeaseUntil, now + 4000);
        display("正在核对原群，判断能否重新定位");
    }

    private void recoverSend(UiNode snapshot, ChatPage chat, long now) {
        if (recoveryCanDismissPicker && WeComMentionPicker.isPicker(snapshot)) {
            recoveryCanDismissPicker = false;
            performGlobalAction(GLOBAL_ACTION_BACK);
            nextScan = now + 600;
            return;
        }
        WeComLayout.DraftState draft = WeComLayout.draftState(chat, job.optString("name"), job.optString("title"));
        if (draft == WeComLayout.DraftState.WRONG_CHAT) return;
        if (!sendRecovery.mayReopen(reservationFor == null, draft)
                || !performGlobalAction(GLOBAL_ACTION_BACK)) {
            finishJob("blocked", draft == WeComLayout.DraftState.DRAFT
                    ? "原群有草稿，请检查后恢复扫描" : "发送页面仍未稳定，请核对后恢复扫描");
            return;
        }
        sendRecovery.reopened();
        pickerSelected = false;
        submittedBody = null;
        resetRound();
        nextScan = now + 600;
        deadline = Math.min(jobLeaseUntil, now + 20000);
        display("原群输入框为空，返回消息列表重新定位本次任务");
    }

    private void inspectResume(long now) {
        if (now >= resumeDeadline) {
            finishResume(false, "恢复未完成，请让手机停在原群并检查草稿");
            return;
        }
        if (!getSystemService(PowerManager.class).isInteractive()
                || getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        if (!targetPackage().contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
            root.recycle();
            return;
        }
        try (Frame frame = new Frame()) {
            UiNode snapshot = frame.copy(root, 0);
            if (!versionSupported()) {
                finishResume(false, "恢复失败，企业微信版本未通过核对");
                return;
            }
            WeComLayout.DraftState draft = WeComLayout.draftState(WeComLayout.identify(snapshot), pausedGroup, pausedTitle);
            if (draft != WeComLayout.DraftState.WRONG_CHAT) {
                boolean empty = draft == WeComLayout.DraftState.EMPTY;
                finishResume(empty, empty
                        ? "原群输入框已核对，恢复扫描"
                        : "原群仍有草稿，请在手机上检查后再恢复");
                return;
            }
            if (resumeOpened || !pausedTitle.equals(verifiedRoutes.get(pausedGroup))) return;
            UiNode list = WeComLayout.conversationList(snapshot);
            if (list == null) return;
            UiNode target = null;
            for (UiNode label : WeComLayout.conversationLabels(list)) {
                if (!pausedGroup.equals(WeixinLayout.label(label))) continue;
                if (target != null) {
                    finishResume(false, "原群名称重复，请在手机上打开正确的群后再恢复");
                    return;
                }
                target = label;
            }
            if (target != null) {
                resumeOpened = true;
                openConversation(frame, list, target);
                nextScan = now + 600;
            }
        } catch (RuntimeException error) {
            nextScan = now + 500;
        }
    }

    private void finishResume(boolean safe, String detail) {
        resumeAck = resumeRequest;
        resumeRequest = null;
        nextSync = 0;
        if (safe) start();
        display(detail);
    }

    private void describeWindow(Frame frame, AccessibilityNodeInfo root, UiNode snapshot) {
        StringBuilder details = new StringBuilder("窗口 ").append(windowClass)
                .append("，根 ").append(snapshot.type).append(' ')
                .append(snapshot.right - snapshot.left).append('x').append(snapshot.bottom - snapshot.top)
                .append("，直系子节点 ").append(root.getChildCount())
                .append("，采到 ").append(frame.held.size())
                .append("，空包名 ").append(frame.missingPackage)
                .append("，其他包 ").append(frame.foreignPackage);
        List<AccessibilityWindowInfo> windows = getWindows();
        details.append("，可交互窗口 ").append(windows.size());
        for (AccessibilityWindowInfo window : windows) {
            if (window.getId() == root.getWindowId()) {
                details.append("，当前窗口类型 ").append(window.getType())
                        .append("，焦点 ").append(window.isFocused());
            }
            window.recycle();
        }
        if (!details.toString().equals(windowDetails)) {
            windowDetails = details.toString();
            trace(windowDetails);
        }
    }

    private void handleList(Frame frame, UiNode list, long now) {
        if (phase == Phase.BACK) phase = Phase.READ;
        List<UiNode> labels = isWeCom() ? WeComLayout.conversationLabels(list) : WeixinLayout.conversationLabels(list);
        if (labels.isEmpty()) {
            display("已识别会话列表，未找到可见会话入口");
            return;
        }
        List<String> names = new ArrayList<>();
        for (UiNode label : labels) names.add(WeixinLayout.label(label));
        if (waitingViewport != null) {
            if (waitingViewport.equals(names)) {
                if (++stuck < 3) { nextScan = now + 450; return; }
                failRound("会话列表滚动后位置未确认");
                return;
            }
            waitingViewport = null;
        }
        if (phase == Phase.TOP_DISCOVER || phase == Phase.TOP_READ) {
            if (scrollList(frame, list, BACKWARD, names, now)) return;
            phase = phase == Phase.TOP_DISCOVER ? Phase.DISCOVER : Phase.READ;
            scrolls = 0;
        }
        if (phase == Phase.DISCOVER) {
            display("正在识别" + targetName() + "会话，本页 " + names.size() + " 个入口");
            for (String name : names) {
                counts.put(name, counts.getOrDefault(name, 0) + 1);
            }
            if (scrollList(frame, list, FORWARD, names, now)) return;
            if (job != null && counts.getOrDefault(job.optString("name"), 0) != 1) {
                failRound("发送目标缺失或同名，无法唯一定位");
                return;
            }
            if (isWeCom() && job == null) {
                forgetMissingNonGroups();
                groupPreviews.keySet().removeIf(name -> counts.getOrDefault(name, 0) != 1);
                groupGapRetries.keySet().retainAll(groupPreviews.keySet());
            }
            trace("会话核对结束，共 " + counts.size() + " 个名称");
            phase = Phase.TOP_READ;
            scrolls = 0;
            return;
        }
        if (phase != Phase.READ) return;
        for (UiNode label : labels) {
            String name = WeixinLayout.label(label);
            if (counts.getOrDefault(name, 0) != 1 || java.util.Collections.frequency(names, name) != 1
                    || visited.contains(name) || (job != null && !job.optString("name").equals(name))) continue;
            UiNode row = isWeCom() && job == null ? conversationRow(frame, list, label) : null;
            if (row != null && WeComLayout.isWorkMessageGuide(row, label)) {
                if (!guideReported) {
                    guideReported = true;
                    trace("跳过企微功能引导入口「接收微信中的工作消息」");
                }
                continue;
            }
            String signature = row == null ? null : WeComLayout.nonGroupRowSignature(row, label);
            if (signature != null && !verifiedRoutes.containsKey(name)
                    && signature.equals(nonGroupRows.getString(name, null))) continue;
            visited.add(name);
            String verifiedTitle = verifiedRoutes.get(name);
            if (row != null && verifiedTitle != null && !WeComLayout.shouldRead(row, label,
                    groupPreviews.get(name), now, groupGapRetries.getOrDefault(name, Long.MAX_VALUE))) {
                foundRoutes.put(name, verifiedTitle);
                unchangedGroups++;
                continue;
            }
            opening = name;
            openingPreview = row == null ? null : WeComLayout.preview(row, label);
            openingMentionPreview = row == null ? null : WeComLayout.directMentionPreview(row);
            openingSignature = signature;
            retriedOpen = false;
            phase = Phase.OPENING;
            nextScan = now + 700;
            openUntil = now + 2200;
            chatScrolls = 0;
            chatSnapshot = null;
            historySweep = null;
            historyPrevious = null;
            historyJoined = null;
            historyRetry = false;
            display((job == null ? "正在打开会话 " : "正在打开回复目标 ") + name);
            if (!openConversation(frame, list, label)) openFailed();
            return;
        }
        if (scrollList(frame, list, FORWARD, names, now)) return;
        if (job != null) { failRound("目标会话已变化"); return; }
        if (isWeCom()) {
            verifiedRoutes = WeComLayout.routesAfterRound(verifiedRoutes, foundRoutes, counts, unopened);
        }
        routes = new JSONObject(isWeCom() ? verifiedRoutes : foundRoutes);
        routesChanged = true;
        idleUntil = now + 15000;
        phase = Phase.IDLE;
        int retained = routes.length() - foundRoutes.size();
        display("已同步 " + (foundRoutes.size() - unchangedGroups) + " 个群"
                + (unchangedGroups > 0 ? "，跳过 " + unchangedGroups + " 个无变化群" : "")
                + (retained > 0 ? "，保留 " + retained + " 个已核对群的回复入口" : "") + "，等待下一轮");
    }

    private void forgetMissingNonGroups() {
        SharedPreferences.Editor edit = nonGroupRows.edit();
        for (String name : nonGroupRows.getAll().keySet()) {
            if (counts.getOrDefault(name, 0) != 1) edit.remove(name);
        }
        edit.apply();
    }

    private UiNode conversationRow(Frame frame, UiNode list, UiNode label) {
        UiNode row = label;
        while (row != null && frame.parents.get(row) != list) row = frame.parents.get(row);
        return row;
    }

    private boolean scrollList(Frame frame, UiNode list, int action, List<String> names, long now) {
        if (scrolls >= 120) { failRound("会话列表已达本轮扫描上限"); return true; }
        AccessibilityNodeInfo node = frame.nodes.get(list);
        if (!node.performAction(action)) return false;
        scrolls++;
        waitingViewport = names;
        stuck = 0;
        nextScan = now + 500;
        return true;
    }

    private boolean openConversation(Frame frame, UiNode list, UiNode label) {
        if (isWeCom()) {
            for (UiNode node = label; node != null && node != list; node = frame.parents.get(node)) {
                if (node.clickable && node.enabled && frame.nodes.get(node).performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    trace("已调用会话行点击");
                    return true;
                }
            }
        }
        return tap(label);
    }

    private boolean retryOpen(Frame frame, UiNode list, long now) {
        if (!isWeCom() || retriedOpen || (job == null && !verifiedRoutes.containsKey(opening))) return false;
        UiNode match = null;
        for (UiNode label : WeComLayout.conversationLabels(list)) {
            if (!opening.equals(WeixinLayout.label(label))) continue;
            if (match != null) return false;
            match = label;
        }
        if (match == null) return false;
        retriedOpen = true;
        trace("会话仍在列表，重试打开");
        if (!tap(match)) return false;
        openUntil = now + 2200;
        nextScan = now + 550;
        return true;
    }

    private void openFailed() {
        if (!isWeCom() && job != null) {
            failRound("目标会话没有打开");
            return;
        }
        if (isWeCom()) trace("会话未打开，仍停留在列表");
        if (job != null) {
            finishJob("blocked", "目标会话没有打开，未填写消息");
            resetRound();
            return;
        }
        unopened.add(opening);
        phase = Phase.READ;
    }

    private boolean tap(UiNode label) {
        float x = (label.left + label.right) / 2f;
        float y = (label.top + label.bottom) / 2f;
        Path path = new Path();
        path.moveTo(x, y);
        return dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 70)).build(), null, null);
    }

    private void handleChat(Frame frame, ChatPage chat, int window, long now) throws JSONException {
        if (chat == null || !opening.equals(chat.name)) {
            if (phase == Phase.OPENING && now < openUntil && (isWeCom() || chat == null)) return;
            if (phase == Phase.VERIFY || phase == Phase.FILL) {
                finishJob(phase == Phase.VERIFY ? "uncertain" : "blocked", "聊天页面已变化");
            } else if (job != null) {
                finishJob("blocked", "群标题与发送目标不符");
            }
            if (phase != Phase.IDLE) back(now);
            return;
        }
        if (!isWeCom()) capture(chat);
        if (job != null) {
            if (!job.optString("title").equals(chat.title)) {
                if (gateway != null) {
                    groupPreviews.remove(chat.name);
                    capture(chat);
                }
                finishJob("blocked", "群名或人数已变化，等待同步核对");
                back(now);
                return;
            }
            send(frame, chat, window, now);
            return;
        }
        phase = Phase.CHAT;
        if (isWeCom() && nonGroupRows.contains(chat.name)) nonGroupRows.edit().remove(chat.name).apply();
        foundRoutes.put(chat.name, chat.title);
        if (gateway != null) {
            readManagedHistory(frame, chat, now);
            return;
        }
        String digest = rows(chat).toString();
        if (chatScrolls < 8 && !digest.equals(chatSnapshot) && spool.length() < MAX_CAPTURES
                && frame.nodes.get(chat.list).performAction(FORWARD)) {
            chatSnapshot = digest;
            chatScrolls++;
            nextScan = now + 500;
            return;
        }
        if (isWeCom()) {
            capture(chat);
            if (openingPreview != null) groupPreviews.put(chat.name, openingPreview);
        }
        back(now);
    }

    private JSONArray rows(ChatPage chat) throws JSONException {
        List<ChatPage.Row> rows = chat.messages();
        JSONArray result = new JSONArray();
        for (int i = Math.max(0, rows.size() - 40); i < rows.size(); i++) {
            ChatPage.Row row = rows.get(i);
            if (row.text.length() > 12000) continue;
            JSONObject message = new JSONObject().put("sender", row.sender == null ? JSONObject.NULL : row.sender)
                    .put("text", row.text);
            if (gateway != null) message.put("direction", row.direction).put("mention_self", "unknown");
            result.put(message);
        }
        return result;
    }

    private JSONObject configuredGroup(String name) {
        JSONArray groups = deviceConfig == null ? null : deviceConfig.optJSONArray("groups");
        if (groups != null) {
            for (int i = 0; i < groups.length(); i++) {
                JSONObject group = groups.optJSONObject(i);
                if (group != null && name.equals(group.optString("name"))) return group;
            }
        }
        return null;
    }

    private void readManagedHistory(Frame frame, ChatPage chat, long now) throws JSONException {
        JSONArray page = rows(chat);
        String digest = page.toString();
        AccessibilityNodeInfo list = frame.nodes.get(chat.list);
        if (historySweep == null && chatScrolls < MAX_FORWARD_WINDOWS && !digest.equals(chatSnapshot)
                && list.performAction(FORWARD)) {
            chatSnapshot = digest;
            chatScrolls++;
            nextScan = now + 600;
            return;
        }
        boolean first = historySweep == null;
        String end = null;
        if (first) {
            historySweep = UUID.randomUUID().toString();
            if (chatScrolls < MAX_FORWARD_WINDOWS) markDirectMention(page);
            historyJoined = page;
            if (chatScrolls >= MAX_FORWARD_WINDOWS) end = "gap";
        } else if (digest.equals(chatSnapshot)) {
            if (!historyRetry && list.performAction(BACKWARD)) {
                historyRetry = true;
                nextScan = now + 700;
                return;
            }
            end = historyRetry ? "gap" : "complete";
        } else {
            historyRetry = false;
            int common = matchingBoundary(page, historyJoined);
            if (common == 0) {
                end = "gap";
            } else {
                JSONArray combined = new JSONArray();
                for (int i = 0; i < page.length() - common; i++) combined.put(page.get(i));
                for (int i = 0; i < historyJoined.length() && combined.length() < 64; i++) combined.put(historyJoined.get(i));
                historyJoined = combined;
            }
        }
        JSONObject group = configuredGroup(chat.name);
        JSONArray anchor = group == null ? null : group.optJSONArray("head");
        if (end == null && group != null && "complete".equals(group.optString("history_status"))
                && anchor != null && anchor.length() > 0 && containsAnchor(historyJoined, anchor)) end = "anchored";
        if (end == null && !scrollOlder(frame, chat)) end = list.performAction(BACKWARD) ? null : "complete";
        String id = first ? historySweep : UUID.randomUUID().toString();
        JSONObject capture = new JSONObject().put("id", id).put("capture_id", id)
                .put("name", chat.name).put("title", chat.title).put("rows", page).put("sweep_id", historySweep);
        if (group != null) capture.put("group_id", group.getString("group_id"));
        if (!first) capture.put("older_than", historyPrevious);
        if (end != null) capture.put("history_status", end);
        spool.put(capture);
        if (!saveBuffers()) throw new IllegalStateException("采集缓存无法保存");
        historyPrevious = id;
        chatSnapshot = digest;
        if (end == null) {
            nextScan = now + 700;
        } else {
            trace("已缓存「" + chat.name + "」采集窗口，状态 " + end);
            if (openingPreview != null) groupPreviews.put(chat.name, openingPreview);
            if ("gap".equals(end)) groupGapRetries.put(chat.name, now + 300000);
            else groupGapRetries.remove(chat.name);
            back(now);
        }
    }

    private void markDirectMention(JSONArray page) throws JSONException {
        if (openingMentionPreview == null || page.length() == 0) return;
        JSONObject match = null;
        for (int i = 0; i < page.length(); i++) {
            JSONObject message = page.getJSONObject(i);
            if (!WeComLayout.matchesDirectMention(openingMentionPreview, message.optString("sender"),
                    message.getString("text"), message.getString("direction"))) continue;
            if (match != null) return;
            match = message;
        }
        if (match == page.getJSONObject(page.length() - 1)) match.put("mention_self", "direct");
    }

    private boolean scrollOlder(Frame frame, ChatPage chat) {
        if ((frame.nodes.get(chat.list).getActions() & BACKWARD) == 0) return false;
        float x = (chat.list.left + chat.list.right) / 2f;
        float height = chat.list.bottom - chat.list.top;
        Path path = new Path();
        path.moveTo(x, chat.list.top + height * .25f);
        path.lineTo(x, chat.list.top + height * .65f);
        return dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 450)).build(), null, null);
    }

    private static boolean sameMessage(JSONObject left, JSONObject right) {
        return left.optString("sender").equals(right.optString("sender"))
                && left.optString("direction").equals(right.optString("direction"))
                && left.optString("text").equals(right.optString("text"));
    }

    private static boolean matches(JSONArray left, int start, JSONArray right, int length) {
        for (int i = 0; i < length; i++) {
            if (!sameMessage(left.optJSONObject(start + i), right.optJSONObject(i))) return false;
        }
        return true;
    }

    private static int matchingBoundary(JSONArray older, JSONArray newer) {
        for (int length = Math.min(older.length(), newer.length()); length > 0; length--) {
            if (matches(older, older.length() - length, newer, length)) return length;
        }
        return 0;
    }

    private static boolean containsAnchor(JSONArray messages, JSONArray anchor) {
        int found = 0;
        for (int i = 0; i <= messages.length() - anchor.length(); i++) {
            if (matches(messages, i, anchor, anchor.length())) found++;
        }
        return found == 1;
    }

    private void capture(ChatPage chat) throws JSONException {
        JSONArray rows = rows(chat);
        String key = chat.title + "\n" + rows;
        if (key.equals(lastRows.get(chat.name))) return;
        String id = UUID.randomUUID().toString();
        JSONObject capture = new JSONObject().put("id", id).put("name", chat.name)
                .put("title", chat.title).put("rows", rows);
        if (gateway != null) {
            capture.put("capture_id", id);
            if (job != null) capture.put("group_id", job.getString("group_id"));
        }
        spool.put(capture);
        if (!saveBuffers()) throw new IllegalStateException("Local storage");
        lastRows.put(chat.name, key);
        trace("已采集「" + chat.name + "」可见文字 " + rows.length() + " 条，等待回传");
    }

    private void back(long now) {
        performGlobalAction(GLOBAL_ACTION_BACK);
        phase = Phase.BACK;
        nextScan = now + 550;
    }

    private void failRound(String reason) {
        if (job != null) finishJob("blocked", reason);
        verifiedRoutes.clear();
        routes = new JSONObject();
        routesChanged = true;
        phase = Phase.IDLE;
        idleUntil = SystemClock.elapsedRealtime() + 15000;
        display(reason);
    }

    private void inspectMemberCollection(UiNode snapshot, ChatPage chat, long now) throws JSONException {
        if (memberSnapshot != null || memberChatSeen == -1) return;
        if (chat != null && chat.name != null) {
            memberChatName = chat.name;
            memberChatTitle = chat.title;
            memberChatSeen = now;
            return;
        }
        if (!WeComMentionPicker.isPicker(snapshot) || memberChatName == null || now - memberChatSeen > 2500) {
            memberChatName = null;
            return;
        }
        JSONObject binding = configuredGroup(memberChatName);
        if (binding == null || !memberChatTitle.equals(binding.optString("title"))) {
            display("群尚未同步或标题已变化。请先返回 APK 启动同步");
            return;
        }
        WeComMentionPicker picker = WeComMentionPicker.identify(snapshot, memberChatTitle);
        if (picker == null) {
            display("名单无法完整核验，未上传成员");
            return;
        }
        queueMemberSnapshot(binding.getString("group_id"), memberChatTitle, picker);
        memberChatSeen = -1;
        display("名单已读取，等待服务器确认");
    }

    private void queueMemberSnapshot(String groupId, String title, WeComMentionPicker picker) throws JSONException {
        memberSnapshot = new JSONObject().put("id", UUID.randomUUID().toString())
                .put("group_id", groupId).put("title", title).put("names", new JSONArray(picker.names));
        nextSync = 0;
    }

    private void handleMentionPicker(Frame frame, UiNode snapshot, ChatPage chat, int window, long now) throws JSONException {
        if (!pickerSelected && WeComMentionPicker.isPicker(snapshot)) {
            WeComMentionPicker picker = WeComMentionPicker.identify(snapshot, job.getString("title"));
            JSONObject target = job.getJSONObject("mention");
            JSONArray roster = target.optJSONArray("roster");
            if (picker != null && roster != null) {
                List<String> expected = new ArrayList<>();
                for (int i = 0; i < roster.length(); i++) expected.add(roster.getString(i));
                if (!picker.matchesRoster(expected)) {
                    queueMemberSnapshot(job.getString("group_id"), job.getString("title"), picker);
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    finishJob("blocked", "群成员名单已变化，请重新查询成员并核对手机草稿");
                    return;
                }
            }
            UiNode member = picker == null ? null : picker.member(target.getString("display_name"));
            if (member == null || !frame.nodes.get(member).performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                finishJob("blocked", "原生选人页无法唯一核对目标成员，请核对草稿");
                return;
            }
            pickerSelected = true;
            nextScan = now + 400;
            return;
        }
        if (chat == null) return;
        String body = job.getString("text");
        String name = job.getJSONObject("mention").getString("display_name");
        if (pickerSelected && window == sendWindow && opening.equals(chat.name)
                && job.getString("title").equals(chat.title)
                && WeComMentionPicker.matchesSelection(body, name, chat.composer.text)) {
            submittedBody = chat.composer.text;
            phase = Phase.FILL;
            deadline = now + 6000;
            trace("已在原生选人页选定成员，回到目标群核对发送");
        }
    }

    private boolean pasteMentionTrigger(AccessibilityNodeInfo input, String prefix) {
        if (Build.VERSION.SDK_INT != 28) return false;
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        if (!input.refresh() || !input.isFocused() || !prefix.contentEquals(input.getText())) return false;
        int end = prefix.length();
        if (input.getTextSelectionStart() != end || input.getTextSelectionEnd() != end) {
            Bundle selection = new Bundle();
            selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, end);
            selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end);
            input.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection);
            if (!input.refresh() || !input.isFocused() || !prefix.contentEquals(input.getText())
                    || input.getTextSelectionStart() != end || input.getTextSelectionEnd() != end) return false;
        }
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData previous = clipboard.getPrimaryClip();
        String label = UUID.randomUUID().toString();
        clipboard.setPrimaryClip(ClipData.newPlainText(label, "@"));
        try {
            return input.performAction(AccessibilityNodeInfo.ACTION_PASTE);
        } finally {
            ClipDescription current = clipboard.getPrimaryClipDescription();
            if (current != null && label.contentEquals(current.getLabel())) {
                if (previous == null) clipboard.clearPrimaryClip();
                else clipboard.setPrimaryClip(previous);
            }
        }
    }

    private void send(Frame frame, ChatPage chat, int window, long now) throws JSONException {
        boolean mention = "SEND_NATIVE_MENTION".equals(job.optString("type"));
        String text = mention && submittedBody != null ? submittedBody : job.optString("text");
        if (phase == Phase.VERIFY) {
            boolean sameWindow = window == sendWindow;
            boolean empty = chat.emptyComposer();
            int matches = countSentReplies(chat, text);
            if (isWeCom() && sameWindow && empty) capture(chat);
            if (sameWindow && empty && matches > baseline) {
                finishJob("visible", "手机已在聊天区读回正文");
                back(now);
            } else if (isWeCom()) {
                String detail = "回读核对。同一窗口 " + sameWindow + "，输入框已清空 " + empty
                        + "，匹配出站气泡 " + matches + "，点击前 " + baseline;
                if (!detail.equals(lastVerifyTrace)) {
                    lastVerifyTrace = detail;
                    trace(detail);
                }
            }
            return;
        }
        if (phase == Phase.PREPARE_MENTION) {
            String prefix = WeComMentionPicker.draftBeforeMention(text);
            if (window != sendWindow || !prefix.equals(chat.composer.text)) {
                finishJob("blocked", "提及前的正文核对失败，请核对草稿");
                return;
            }
            if (!pasteMentionTrigger(frame.nodes.get(chat.composer), prefix)) {
                finishJob("blocked", "无法在正文末尾触发原生选人，请核对草稿");
                return;
            }
            phase = Phase.PICK_MENTION;
            deadline = now + 6000;
            trace("已填写正文，等待末尾的原生选人页");
            return;
        }
        if (phase != Phase.FILL) {
            if (!chat.composer.editable || !chat.emptyComposer()) {
                finishJob("blocked", "输入框不可编辑或已有草稿");
                back(now);
                return;
            }
            AccessibilityNodeInfo input = frame.nodes.get(chat.composer);
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    mention ? WeComMentionPicker.draftBeforeMention(text) : text);
            if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                finishJob("blocked", "微信输入框不支持填写"); back(now); return;
            }
            sendWindow = window;
            phase = mention ? Phase.PREPARE_MENTION : Phase.FILL;
            deadline = now + 6000;
            trace(mention ? "已填写正文，等待核对输入框" : "已填写发送任务，等待核对输入框和发送按钮");
            return;
        }
        if (sendWindow != window || (!chat.emptyComposer() && !text.equals(chat.composer.text))) {
            finishJob("blocked", "填写后窗口或正文变化，请核对草稿");
            return;
        }
        if (chat.emptyComposer()) return;
        UiNode button = chat.sendButton();
        if (button == null) return;
        String id = job.optString("id");
        if (gateway != null && !id.equals(reservationFor)) {
            if (!recordClickIntent(id)) {
                finishJob("blocked", "本地点击日志保存失败");
                return;
            }
            requestReservation(id, now);
            return;
        }
        if (gateway != null && (!mayClick || now >= reservationUntil || now >= jobLeaseUntil || !online)) return;
        baseline = countSentReplies(chat, text);
        if (gateway == null && !recordClickIntent(id)) {
            finishJob("blocked", "本地点击日志保存失败");
            return;
        }
        mayClick = false;
        phase = Phase.VERIFY;
        lastVerifyTrace = null;
        deadline = now + 8000;
        trace("已记下点击意图，等待聊天区回读");
        if (!frame.nodes.get(button).performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            finishJob("uncertain", "微信未确认点击动作");
        }
    }

    private int countSentReplies(ChatPage chat, String text) throws JSONException {
        if (!"SEND_NATIVE_MENTION".equals(job.optString("type"))) return chat.countBody(text);
        String body = job.getString("text");
        String name = job.getJSONObject("mention").getString("display_name");
        int matches = 0;
        for (ChatPage.Row row : chat.messages()) {
            if ("outgoing".equals(row.direction) && WeComMentionPicker.matchesSelection(body, name, row.text)) matches++;
        }
        return matches;
    }

    private boolean recordClickIntent(String id) {
        return buffers.edit().putString("click_pending_id", id)
                .putString("click_pending_group", job.optString("group_id")).commit();
    }

    private void requestReservation(String id, long now) {
        reservationFor = id;
        deadline = now + 10000;
        DeviceGateway active = gateway;
        JSONObject command = job;
        long currentGeneration = generation;
        network.execute(() -> {
            boolean allowed;
            try { allowed = active.reserve(command); }
            catch (Exception error) { allowed = false; }
            boolean granted = allowed;
            handler.post(() -> {
                if (generation != currentGeneration || job != command || phase != Phase.FILL) return;
                if (granted) {
                    mayClick = true;
                    reservationUntil = Math.min(jobLeaseUntil, SystemClock.elapsedRealtime() + 2000);
                    nextScan = 0;
                } else {
                    finishJob("uncertain", "发送许可未确认");
                    resetRound();
                }
            });
        });
    }

    private void finishJob(String state, String detail) {
        boolean draftHold = isWeCom() && !"uncertain".equals(state)
                && (phase == Phase.FILL || phase == Phase.PREPARE_MENTION
                    || phase == Phase.PICK_MENTION || phase == Phase.RECOVER_SEND);
        if (draftHold) {
            pausedGroup = job.optString("name");
            pausedTitle = job.optString("title");
        }
        completion = result(job.optString("id"), state, detail);
        if (gateway != null) {
            try {
                completion.put("group_id", job.getString("group_id"))
                        .put("event_id", UUID.randomUUID().toString());
                if ("visible".equals(state)) completion.put("state", "confirmed");
            } catch (JSONException error) { throw new IllegalStateException(error); }
        }
        job = null;
        reservationFor = null;
        mayClick = false;
        saveBuffers();
        if ("uncertain".equals(state) && isWeCom()) {
            // The result is never reissued. Resume scanning after reporting uncertainty.
            phase = Phase.IDLE;
            idleUntil = SystemClock.elapsedRealtime() + 2000;
        } else if ("uncertain".equals(state) || draftHold || phase == Phase.FILL
                || phase == Phase.PREPARE_MENTION || phase == Phase.PICK_MENTION) {
            if (isWeCom()) this.state.edit().putBoolean("resume_scanning", false).apply();
            phase = Phase.IDLE;
            idleUntil = Long.MAX_VALUE;
        }
        display(draftHold ? "扫描已暂停，请核对草稿后恢复 · " + detail : detail);
    }

    private static JSONObject result(String id, String state, String detail) {
        try { return new JSONObject().put("id", id).put("state", state).put("detail", detail); }
        catch (JSONException error) { throw new IllegalStateException(error); }
    }

    private boolean saveBuffers() {
        SharedPreferences.Editor editor = buffers.edit().putString("spool", spool.toString())
                .putString("completion", completion == null ? "" : completion.toString());
        if (completion != null && completion.optString("id").equals(buffers.getString("click_pending_id", ""))) {
            editor.remove("click_pending_id").remove("click_pending_group");
        }
        return editor.commit();
    }

    private void sync() {
        try {
            JSONArray batch = new JSONArray();
            if (spool.length() > 0) batch.put(spool.getJSONObject(0));
            JSONObject routeSnapshot = routesChanged && spool.length() <= 1 ? routes : null;
            JSONObject request = new JSONObject().put("platform", isWeCom() ? "wecom" : "weixin").put("captures", batch)
                    .put("status", status).put("ready", !diagnosing && job == null && completion == null && spool.length() == 0
                            && !collectingMembers && phase == Phase.IDLE && idleUntil != Long.MAX_VALUE && (!isWeCom() || foregroundList));
            if (gateway != null) request.put("scan_paused", pausedGroup != null).put("resume_ack", resumeAck);
            if (routeSnapshot != null) request.put("routes", routeSnapshot);
            if (completion != null) request.put("completion", completion);
            if (memberSnapshot != null) request.put("member_snapshot", memberSnapshot);
            if (gateway != null) request.put("need_config", deviceConfig == null || batch.length() > 0);
            request.put("events", new JSONArray(events.subList(0, Math.min(events.size(), 30))));
            String body = request.toString();
            long currentGeneration = generation;
            long requestStarted = SystemClock.elapsedRealtime();
            DeviceGateway activeGateway = gateway;
            syncing = true;
            nextSync = SystemClock.elapsedRealtime() + 1000;
            network.execute(() -> {
                JSONObject response = null;
                String failure = "";
                try { response = activeGateway == null ? exchange(body) : activeGateway.sync(new JSONObject(body)); }
                catch (Exception error) {
                    failure = error.getClass().getSimpleName();
                    if (error.getMessage() != null && error.getMessage().matches("HTTP [0-9]+")) failure = error.getMessage();
                }
                JSONObject received = response;
                String problem = failure;
                handler.post(() -> {
                    syncing = false;
                    if (!running || generation != currentGeneration) return;
                    if (received != null && "pending_approval".equals(received.optString("status"))) {
                        online = false;
                        display("等待 Web 管理员通过接入");
                        return;
                    }
                    online = received != null;
                    if (received == null) { display("本地网页不可达，等待重连（" + problem + "）"); return; }
                    if (status.equals("正在连接本地网页") || status.startsWith("本地网页不可达")) {
                        display("已连接本地网页，等待" + targetName());
                    }
                    if (received.has("config")) deviceConfig = received.optJSONObject("config");
                    String resumeId = received.optString("resume_scan_id");
                    if (pausedGroup != null && resumeRequest == null && !resumeId.equals(resumeAck)
                            && resumeId.matches("[a-f0-9]{32}")) {
                        resumeRequest = resumeId;
                        resumeOpened = false;
                        resumeDeadline = SystemClock.elapsedRealtime() + 10000;
                        nextScan = 0;
                        display("正在核对原群草稿");
                    }
                    JSONArray ack = received.optJSONArray("ack");
                    JSONObject head = spool.optJSONObject(0);
                    if (head != null && ack != null && head.optString("id").equals(ack.optString(0))) spool.remove(0);
                    JSONArray eventAck = received.optJSONArray("event_ack");
                    if (eventAck != null) {
                        Set<String> ids = new HashSet<>();
                        for (int i = 0; i < eventAck.length(); i++) ids.add(eventAck.optString(i));
                        events.removeIf(event -> ids.contains(event.optString("id")));
                    }
                    if (completion != null && completion.optString("id").equals(received.optString("completion_ack"))) completion = null;
                    if (memberSnapshot != null && memberSnapshot.optString("id").equals(received.optString("member_snapshot_ack"))) {
                        memberSnapshot = null;
                        if (collectingMembers) {
                            display("群成员已同步。请退出选人页并清空草稿，再返回 APK 恢复扫描");
                        } else {
                            trace("已同步变化后的成员名单");
                        }
                    }
                    if (routeSnapshot == routes) routesChanged = false;
                    saveBuffers();
                    JSONObject next = received.optJSONObject("job");
                    if (next != null && job == null) {
                        job = next;
                        sendRecovery.reset();
                        pickerSelected = false;
                        submittedBody = null;
                        trace("收到网页发送任务 " + job.optString("id").substring(0, Math.min(8, job.optString("id").length())));
                        long receivedAt = SystemClock.elapsedRealtime();
                        jobLeaseUntil = requestStarted + job.optLong("lease_remaining_ms", 80000) - 1000;
                        deadline = receivedAt + 80000;
                        resetRound();
                    }
                });
            });
        } catch (JSONException error) { display("同步数据无效"); }
    }

    private JSONObject exchange(String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(4000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setDoOutput(true);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            int code = connection.getResponseCode();
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            try (InputStream input = connection.getInputStream()) { return new JSONObject(readString(input, 8192)); }
        } finally { connection.disconnect(); }
    }

    private static String readString(InputStream input, int limit) throws java.io.IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int size;
        while ((size = input.read(buffer)) != -1) {
            if (bytes.size() + size > limit) throw new java.io.IOException("Response too large");
            bytes.write(buffer, 0, size);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private boolean versionSupported() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(targetPackage(), 0);
            return (Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode) == (isWeCom() ? WeComLayout.VERSION : WeixinLayout.VERSION);
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { return false; }
    }

    private void display(String value) {
        if (!value.equals(status)) {
            status = value;
            state.edit().putString("status", value).apply();
            trace(value);
        }
    }

    private void trace(String text) {
        try {
            events.add(new JSONObject().put("id", UUID.randomUUID().toString()).put("text", text));
            if (events.size() > 100) events.remove(0);
        } catch (JSONException error) { throw new IllegalStateException(error); }
        String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date());
        String previous = state.getString("diagnostic_log", "");
        String log = time + "  " + text + "\n" + previous;
        state.edit().putString("diagnostic_log", log.substring(0, Math.min(6000, log.length()))).apply();
    }

    private final class Frame implements AutoCloseable {
        final Map<UiNode, AccessibilityNodeInfo> nodes = new IdentityHashMap<>();
        final Map<UiNode, UiNode> parents = new IdentityHashMap<>();
        final List<AccessibilityNodeInfo> held = new ArrayList<>();
        int missingPackage, foreignPackage;

        UiNode copy(AccessibilityNodeInfo source, int depth) {
            held.add(source);
            if (depth > 60 || held.size() > 2000) throw new IllegalStateException("UI tree limit");
            UiNode node = new UiNode();
            nodes.put(node, source);
            node.id = value(source.getViewIdResourceName());
            node.type = value(source.getClassName());
            node.text = value(source.getText());
            node.description = value(source.getContentDescription());
            node.visible = source.isVisibleToUser();
            node.editable = source.isEditable();
            node.enabled = source.isEnabled();
            node.clickable = source.isClickable();
            node.scrollable = source.isScrollable();
            node.hint = Build.VERSION.SDK_INT >= 26 && source.isShowingHintText();
            Rect rect = new Rect();
            source.getBoundsInScreen(rect);
            node.left = rect.left; node.top = rect.top; node.right = rect.right; node.bottom = rect.bottom;
            for (int i = 0; i < source.getChildCount(); i++) {
                AccessibilityNodeInfo child = source.getChild(i);
                if (child == null) throw new IllegalStateException("UI tree changed");
                String childPackage = value(child.getPackageName());
                if (childPackage.isEmpty()) missingPackage++;
                if (!childPackage.isEmpty() && !targetPackage().equals(childPackage)) {
                    foreignPackage++;
                    child.recycle();
                    continue;
                }
                UiNode snapshot = copy(child, depth + 1);
                node.children.add(snapshot);
                if (isWeCom()) parents.put(snapshot, node);
            }
            return node;
        }

        private String value(CharSequence value) { return value == null ? "" : value.toString(); }

        @Override public void close() {
            for (AccessibilityNodeInfo node : held) node.recycle();
        }
    }
}
