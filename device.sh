#!/bin/sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
default_adb="$HOME/Library/Caches/wecom-android-probe/sdk/platform-tools/adb"
adb_path=${WECOM_PROBE_ADB:-"$default_adb"}
probe_package=local.mdnode.wecomprobe
apk="$project_dir/dist/wecom-readonly-probe.apk"
action=${1:-check}

case "$action" in
    check|install|logs|data) ;;
    *) printf '%s\n' '用法：sh device.sh [check|install|logs|data]' >&2; exit 2 ;;
esac

if [ ! -x "$adb_path" ] && [ -z "${WECOM_PROBE_ADB:-}" ]; then
    adb_path=$(command -v adb || true)
fi
if [ -z "$adb_path" ] || [ ! -x "$adb_path" ]; then
    printf '%s\n' 'ADB 不可用。请安装 Android Platform Tools 或设置 WECOM_PROBE_ADB。' >&2
    exit 1
fi

devices=$("$adb_path" devices)
serial=${WECOM_PROBE_SERIAL:-}
if [ -z "$serial" ]; then
    count=$(printf '%s\n' "$devices" | awk 'NR > 1 && NF >= 2 { n++ } END { print n+0 }')
    case "$count" in
        0) printf '%s\n' '未检测到安卓设备。请连接数据线并开启 USB 调试。' >&2; exit 1 ;;
        1) serial=$(printf '%s\n' "$devices" | awk 'NR > 1 && NF >= 2 { print $1; exit }') ;;
        *) printf '%s\n' '检测到多台设备。请用 WECOM_PROBE_SERIAL 指定测试手机。' >&2; exit 1 ;;
    esac
fi
connection=$(printf '%s\n' "$devices" | awk -v target="$serial" 'NR > 1 && $1 == target { print $2; exit }')
case "$connection" in
    device) ;;
    unauthorized) printf '%s\n' '手机尚未授权 USB 调试。请解锁并确认手机上的授权提示。' >&2; exit 1 ;;
    offline) printf '%s\n' '手机当前处于离线状态。请重新连接数据线。' >&2; exit 1 ;;
    *) printf '%s\n' '指定的测试手机不可用。' >&2; exit 1 ;;
esac

device() { "$adb_path" -s "$serial" "$@"; }

device_shell() {
    output=$(device shell "$@") || return
    printf '%s\n' "$output" | tr -d '\r'
}

package_version() {
    details=$(device_shell dumpsys package "$1") || return
    printf '%s\n' "$details" | awk -F= '/versionName=/ { print $2; exit }'
}

case "$action" in
    check)
        release=$(device_shell getprop ro.build.version.release)
        api=$(device_shell getprop ro.build.version.sdk)
        wecom=$(package_version com.tencent.wework)
        probe=$(package_version "$probe_package")
        enabled=$(device_shell settings get secure enabled_accessibility_services)
        case ":$enabled:" in
            *":$probe_package/.ProbeService:"*|*":$probe_package/$probe_package.ProbeService:"*) service=已开启 ;;
            *) service=未开启 ;;
        esac
        printf 'Android %s（API %s）\n企业微信 %s\n只读探针 %s，无障碍服务%s\n' \
            "$release" "$api" "${wecom:-未安装}" "${probe:-未安装}" "$service"
        ;;
    install)
        if [ ! -f "$apk" ]; then
            printf '%s\n' '找不到测试 APK。请先运行 sh build.sh。' >&2
            exit 1
        fi
        device install -r "$apk"
        ;;
    logs)
        device logcat -d -s 'WecomReadProbe:I' '*:S'
        ;;
    data)
        device exec-out run-as "$probe_package" cat files/reader-export.json
        ;;
esac
