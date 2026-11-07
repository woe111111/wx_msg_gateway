package local.mdnode.wecomprobe;

final class ProbeStatus {
    static String label(String phase) {
        switch (phase) {
            case "STOPPED": return "已停止";
            case "WAITING_FOR_ACCESSIBILITY": return "请开启无障碍服务";
            case "WAITING_FOR_WECOM": return "请打开名单中的企微群";
            case "LOCKED": return "屏幕关闭或已锁屏";
            case "WRONG_GROUP": return "当前群不在配置名单中";
            case "UNSUPPORTED_LAYOUT": return "当前企微版本或布局不支持";
            case "READING": return "正在读取普通文本";
            case "STORAGE_FULL": return "本地记录已满，请先导出";
            default: return "读取暂停（" + phase + "）";
        }
    }
}
