#!/bin/sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
sdk_dir=${ANDROID_HOME:-"$HOME/Library/Caches/wecom-android-probe/sdk"}
tools_dir="$sdk_dir/build-tools/35.0.0"
platform="$sdk_dir/platforms/android-35/android.jar"
out_dir="$project_dir/dist"
cache_dir="$HOME/Library/Caches/wecom-android-probe"
key="$cache_dir/debug.keystore"
manifest="$project_dir/AndroidManifest.xml"
resources="$project_dir/res"
apk_name=wecom-readonly-probe.apk
case "${1:-wecom}" in
    wecom) ;;
    weixin)
        manifest="$project_dir/weixin/AndroidManifest.xml"
        resources="$project_dir/weixin/res"
        apk_name=weixin-test-probe.apk
        ;;
    wecom-web)
        manifest="$project_dir/wecom-web/AndroidManifest.xml"
        resources="$project_dir/wecom-web/res"
        apk_name=wecom-web-probe.apk
        ;;
    *) printf 'Usage: %s [wecom|weixin|wecom-web]\n' "$0" >&2; exit 2 ;;
esac

umask 077
mkdir -p "$out_dir" "$cache_dir"
build_dir=$(mktemp -d "${TMPDIR:-/tmp}/wecom-probe-build.XXXXXX")
trap 'find "$build_dir" -depth -delete' EXIT
mkdir "$build_dir/classes" "$build_dir/dex"
mkdir "$build_dir/assets"
case "$apk_name" in
    weixin-test-probe.apk) gateway_config=${WEIXIN_GATEWAY_CONFIG:-} ;;
    *) gateway_config= ;;
esac
if [ -n "$gateway_config" ]; then
    cp "$gateway_config" "$build_dir/assets/gateway.json"
fi

javac --release 8 -d "$build_dir/tests" \
    "$project_dir/src/local/mdnode/wecomprobe/ProbeText.java" \
    "$project_dir/src/local/mdnode/wecomprobe/UiNode.java" \
    "$project_dir/src/local/mdnode/wecomprobe/MessageExtractor.java" \
    "$project_dir/src/local/mdnode/wecomprobe/MessageFieldDump.java" \
    "$project_dir/src/local/mdnode/wecomprobe/MessageTracker.java" \
    "$project_dir/src/local/mdnode/wecomprobe/ReaderConfig.java" \
    "$project_dir/src/local/mdnode/wecomprobe/WeixinLayout.java" \
    "$project_dir/src/local/mdnode/wecomprobe/ChatPage.java" \
    "$project_dir/src/local/mdnode/wecomprobe/WeComLayout.java" \
    "$project_dir/src/local/mdnode/wecomprobe/WeComMentionPicker.java" \
    "$project_dir/src/local/mdnode/wecomprobe/WeComSendRecovery.java" \
    "$project_dir"/tests/*.java
java -cp "$build_dir/tests" local.mdnode.wecomprobe.ProbeTextTest
java -cp "$build_dir/tests" local.mdnode.wecomprobe.MessageReaderTest
java -cp "$build_dir/tests" local.mdnode.wecomprobe.WeixinLayoutTest
java -cp "$build_dir/tests" local.mdnode.wecomprobe.WeComLayoutTest
java -cp "$build_dir/tests" local.mdnode.wecomprobe.WeComMentionPickerTest
java -cp "$build_dir/tests" local.mdnode.wecomprobe.ReplayUiDump \
    "$project_dir/tests/fixtures/wecom_84802_text_and_rich.xml" '测试群' 1

"$tools_dir/aapt2" compile --dir "$resources" -o "$build_dir/res.zip"
"$tools_dir/aapt2" link -I "$platform" --manifest "$manifest" -A "$build_dir/assets" \
    -o "$build_dir/unsigned.apk" "$build_dir/res.zip"
javac --release 8 -classpath "$platform" -d "$build_dir/classes" \
    "$project_dir"/src/local/mdnode/wecomprobe/*.java
jar cf "$build_dir/classes.jar" -C "$build_dir/classes" .
"$tools_dir/d8" --lib "$platform" --min-api 25 --output "$build_dir/dex" "$build_dir/classes.jar"
(cd "$build_dir/dex" && zip -q "$build_dir/unsigned.apk" classes.dex)
"$tools_dir/zipalign" -f 4 "$build_dir/unsigned.apk" "$build_dir/aligned.apk"

if [ ! -f "$key" ]; then
    keytool -genkeypair -keystore "$key" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Android Debug'
fi
"$tools_dir/apksigner" sign --ks "$key" --ks-key-alias androiddebugkey \
    --ks-pass pass:android --key-pass pass:android --out "$build_dir/$apk_name" \
    "$build_dir/aligned.apk"
"$tools_dir/apksigner" verify "$build_dir/$apk_name"
mv "$build_dir/$apk_name" "$out_dir/$apk_name"
printf '%s\n' "$out_dir/$apk_name"
