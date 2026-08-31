#!/usr/bin/env bash
set -euo pipefail

project_dir=$(cd "$(dirname "$0")/.." && pwd)
cd "$project_dir"

if [[ -z "${ANDROID_SDK_ROOT:-}" && -z "${ANDROID_HOME:-}" ]]; then
    macos_android_sdk="${HOME:?}/Library/Android/sdk"
    if [[ -d "$macos_android_sdk" ]]; then
        export ANDROID_SDK_ROOT="$macos_android_sdk"
        export ANDROID_HOME="$macos_android_sdk"
    fi
fi

version_name=$(awk -F= '$1 == "VERSION_NAME" { print $2 }' version.properties)
version_code=$(awk -F= '$1 == "VERSION_CODE" { print $2 }' version.properties)
expected_version=$(printf 'versionName=%s\nversionCode=%s' "$version_name" "$version_code")
actual_version=$(./gradlew -q printVersion)
if [[ "$actual_version" != "$expected_version" ]]; then
    echo "Unexpected version output:"
    echo "$actual_version"
    exit 1
fi

./gradlew -q verifyReleaseTag -PreleaseTag="v$version_name"
if ./gradlew -q verifyReleaseTag -PreleaseTag="v$version_name-mismatch" >/dev/null 2>&1; then
    echo "Mismatched release tag was accepted"
    exit 1
fi

release_test_dir=$(mktemp -d "${TMPDIR:-/tmp}/reva-release-test.XXXXXX")
apk_path="$project_dir/app/build/outputs/apk/release/app-release.apk"
cleanup() {
    rm -rf "$release_test_dir"
    rm -f "$apk_path"
}
trap cleanup EXIT

test_keystore="$release_test_dir/test-release.jks"
test_password="synthetic-release-password"
keytool -genkeypair \
    -keystore "$test_keystore" \
    -storetype JKS \
    -storepass "$test_password" \
    -keypass "$test_password" \
    -alias reva-health-exporter-test \
    -keyalg RSA \
    -keysize 2048 \
    -validity 1 \
    -dname "CN=Synthetic Release Test" \
    -noprompt >/dev/null 2>&1

export ANDROID_KEYSTORE_PATH="$test_keystore"
export ANDROID_KEYSTORE_PASSWORD="$test_password"
export ANDROID_KEY_ALIAS="reva-health-exporter-test"
export ANDROID_KEY_PASSWORD="$test_password"

./gradlew clean test lintRelease assembleRelease

if [[ ! -f "$apk_path" ]]; then
    echo "Signed release APK was not created at $apk_path"
    exit 1
fi
mapping_path="$project_dir/app/build/outputs/mapping/release/mapping.txt"
if [[ ! -s "$mapping_path" ]]; then
    echo "R8 mapping was not created at $mapping_path; the release may not be minified"
    exit 1
fi

android_sdk_dir=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
if [[ -z "$android_sdk_dir" ]]; then
    echo "ANDROID_SDK_ROOT or ANDROID_HOME is required"
    exit 1
fi
build_tools_dir=$(find "$android_sdk_dir/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)
apksigner_path="$build_tools_dir/apksigner"
aapt2_path="$build_tools_dir/aapt2"
if [[ ! -x "$apksigner_path" ]]; then
    echo "apksigner was not found in $android_sdk_dir/build-tools"
    exit 1
fi
if [[ ! -x "$aapt2_path" ]]; then
    echo "aapt2 was not found in $android_sdk_dir/build-tools"
    exit 1
fi

"$apksigner_path" verify --verbose --print-certs "$apk_path"
apk_badging=$("$aapt2_path" dump badging "$apk_path")
if [[ "$apk_badging" != *"versionCode='$version_code'"* || "$apk_badging" != *"versionName='$version_name'"* ]]; then
    echo "APK manifest version does not match version.properties"
    exit 1
fi
