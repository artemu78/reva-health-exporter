#!/usr/bin/env bash
set -euo pipefail

project_dir=$(cd "$(dirname "$0")/.." && pwd)
cd "$project_dir"

android_sdk_dir=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
if [[ -z "$android_sdk_dir" ]]; then
    echo "ANDROID_SDK_ROOT or ANDROID_HOME is required"
    exit 1
fi
adb="$android_sdk_dir/platform-tools/adb"
if [[ ! -x "$adb" ]]; then
    echo "adb was not found at $adb"
    exit 1
fi

launch_test_dir=$(mktemp -d "${TMPDIR:-/tmp}/reva-minified-launch.XXXXXX")
apk_path="$project_dir/app/build/outputs/apk/release/app-release.apk"
cleanup() {
    rm -rf "$launch_test_dir"
    rm -f "$apk_path"
}
trap cleanup EXIT

test_keystore="$launch_test_dir/minified-launch.jks"
test_password="synthetic-minified-launch-password"
keytool -genkeypair \
    -keystore "$test_keystore" \
    -storetype JKS \
    -storepass "$test_password" \
    -keypass "$test_password" \
    -alias reva-minified-launch \
    -keyalg RSA \
    -keysize 2048 \
    -validity 1 \
    -dname "CN=Synthetic Minified Launch" \
    -noprompt >/dev/null 2>&1

export ANDROID_KEYSTORE_PATH="$test_keystore"
export ANDROID_KEYSTORE_PASSWORD="$test_password"
export ANDROID_KEY_ALIAS="reva-minified-launch"
export ANDROID_KEY_PASSWORD="$test_password"

./gradlew assembleRelease
"$adb" install -r "$apk_path"
"$adb" shell am force-stop dev.reva.healthexporter
"$adb" shell am start -W -n dev.reva.healthexporter/.MainActivity >/dev/null
sleep 2

if ! "$adb" shell pidof dev.reva.healthexporter >/dev/null; then
    echo "Minified release process exited during startup"
    "$adb" logcat -d -T 5 'AndroidRuntime:E' '*:S' | tail -80
    exit 1
fi

echo "Minified release launched successfully on API $("$adb" shell getprop ro.build.version.sdk)."
