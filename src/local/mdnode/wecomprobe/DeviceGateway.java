package local.mdnode.wecomprobe;

import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.json.JSONObject;

final class DeviceGateway {
    final String clientId, base, key;
    private final String instance = UUID.randomUUID().toString();
    private String session;
    private long epoch;

    DeviceGateway(String ip, int port, String clientId, String key, boolean https) {
        if (!validIp(ip) || port < 1 || port > 65535) throw new IllegalArgumentException("请填写有效的服务器 IP 和端口");
        if (!clientId.matches("[A-Za-z0-9_-]{1,64}") || !key.matches("[A-Za-z0-9_-]{16,160}")) {
            throw new IllegalArgumentException("请填写有效的 Client ID 和注册 Key");
        }
        this.clientId = clientId;
        this.key = key;
        this.base = (https ? "https://" : "http://") + (ip.indexOf(':') >= 0 ? "[" + ip + "]" : ip) + ":" + port;
    }

    private static boolean validIp(String ip) {
        if (ip.indexOf(':') >= 0 && ip.matches("[0-9A-Fa-f:.]+")) {
            try { InetAddress.getByName(ip); return true; } catch (IOException ignored) { return false; }
        }
        if (!ip.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        for (String part : ip.split("\\.")) if (Integer.parseInt(part) > 255) return false;
        return true;
    }

    boolean sameCredentials(DeviceGateway other) {
        return other != null && base.equals(other.base) && clientId.equals(other.clientId) && key.equals(other.key);
    }

    JSONObject sync(JSONObject packet) throws Exception {
        try {
            if (session == null) {
                JSONObject registration = request("POST", "/v1/devices/session", new JSONObject()
                        .put("client_id", clientId).put("instance_id", instance).put("app_version", "1.8")
                        .put("capabilities", capabilities()));
                if ("pending_approval".equals(registration.optString("status"))) return registration;
                session = registration.getString("device_session_id");
                epoch = registration.getLong("lease_epoch");
            }
            JSONObject heartbeat = request("POST", "/v1/devices/heartbeat", new JSONObject()
                    .put("status", packet.optString("status"))
                    .put("scan_paused", packet.optBoolean("scan_paused"))
                    .put("resume_ack", packet.optString("resume_ack")));
            JSONObject result = request("POST", "/v1/device-results", packet);
            if (heartbeat.has("resume_scan_id")) result.put("resume_scan_id", heartbeat.getString("resume_scan_id"));
            if (packet.optBoolean("ready")) {
                JSONObject command = request("POST", "/v1/device-commands/claim", new JSONObject().put("ready", true));
                if (command != null) {
                    if (!clientId.equals(command.getString("client_id")) || !validCommandType(command)
                            || !command.getString("command_id").matches("[a-f0-9]{32}")
                            || !bodyDigest(command.getString("text")).equals(command.getString("body_digest"))) {
                        throw new IOException("命令与设备不匹配");
                    }
                    command.put("id", command.getString("command_id"));
                    result.put("job", command);
                }
            }
            if (packet.optBoolean("need_config")) result.put("config", request("GET", "/v1/devices/config", null));
            return result;
        } catch (HttpFailure failure) {
            if (failure.code == 401 || failure.code == 403 || failure.code == 409) session = null;
            throw failure;
        }
    }

    private static org.json.JSONArray capabilities() {
        org.json.JSONArray result = new org.json.JSONArray().put("READ_GROUP_WINDOW").put("SEND_TEXT");
        if (Build.VERSION.SDK_INT == 28) result.put("SEND_NATIVE_MENTION").put("MENTION_MESSAGE_SENDER");
        return result;
    }

    private static boolean validCommandType(JSONObject command) {
        if ("SEND_TEXT".equals(command.optString("type"))) return !command.has("mention");
        if (Build.VERSION.SDK_INT != 28 || !"SEND_NATIVE_MENTION".equals(command.optString("type"))) return false;
        JSONObject member = command.optJSONObject("mention");
        if (member == null) return false;
        if (member.has("source_message_id")) {
            if (!member.optString("source_message_id").matches("[a-f0-9]{32}")
                    || member.has("roster") || member.has("member_id")) return false;
        } else if (member.optJSONArray("roster") == null
                || !member.optString("member_id").matches("mem_[a-f0-9]{64}")) return false;
        String name = member.optString("display_name");
        return !name.trim().isEmpty() && name.length() <= 120 && !name.contains("\n");
    }

    boolean reserve(JSONObject job) throws Exception {
        if (session == null) return false;
        JSONObject proof = new JSONObject().put("group_id", job.getString("group_id"))
                .put("title", job.getString("title")).put("body_digest", job.getString("body_digest"));
        JSONObject mention = job.optJSONObject("mention");
        if (mention != null) {
            String field = mention.has("source_message_id") ? "source_message_id" : "member_id";
            proof.put(field, mention.getString(field));
        }
        return request("POST", "/v1/device-commands/" + job.getString("command_id") + "/click-reservation",
                proof).optBoolean("may_click");
    }

    private static String bodyDigest(String body) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(64);
        for (byte value : bytes) {
            result.append(Character.forDigit((value & 255) >>> 4, 16));
            result.append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }

    private JSONObject request(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(4000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Authorization", "Bearer " + key);
            connection.setRequestProperty("X-Client-Id", clientId);
            if (session != null) {
                connection.setRequestProperty("X-Device-Session", session);
                connection.setRequestProperty("X-Lease-Epoch", Long.toString(epoch));
            }
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            if (status == 204) return null;
            if (status != 200 && status != 202) throw new HttpFailure(status);
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[2048];
                for (int count; (count = input.read(buffer)) != -1;) {
                    if (bytes.size() + count > 2_000_000) throw new IOException("响应过大");
                    bytes.write(buffer, 0, count);
                }
                return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally { connection.disconnect(); }
    }

    static final class HttpFailure extends IOException {
        final int code;
        HttpFailure(int code) { super("HTTP " + code); this.code = code; }
    }
}
