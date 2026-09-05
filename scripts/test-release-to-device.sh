#!/usr/bin/env bash
set -euo pipefail

project_dir=$(cd "$(dirname "$0")/.." && pwd)
script_under_test="$project_dir/scripts/release-to-device.sh"
test_version_name=$(awk -F= '$1 == "VERSION_NAME" { print $2 }' "$project_dir/version.properties")
test_version_code=$(awk -F= '$1 == "VERSION_CODE" { print $2 }' "$project_dir/version.properties")
export REVA_TEST_VERSION_NAME="$test_version_name"
export REVA_TEST_VERSION_CODE="$test_version_code"

test_dir=$(mktemp -d "${TMPDIR:-/tmp}/reva-release-to-device-test.XXXXXX")
cleanup() {
    rm -rf "$test_dir"
}
trap cleanup EXIT

fake_bin="$test_dir/bin"
command_log="$test_dir/commands.log"
device_state_file="$test_dir/device-state"
download_dir="$test_dir/downloads"
mkdir -p "$fake_bin" "$download_dir"

cat >"$fake_bin/git" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'git %s\n' "$*" >>"$REVA_TEST_COMMAND_LOG"
case "$*" in
    "branch --show-current") printf 'main\n' ;;
    "status --porcelain") ;;
    "fetch origin main") ;;
    "rev-parse HEAD"|"rev-parse origin/main"|"rev-parse v${REVA_TEST_VERSION_NAME}^{}") printf '0123456789abcdef\n' ;;
    "tag --list v${REVA_TEST_VERSION_NAME}")
        [[ "${REVA_TEST_LOCAL_TAG_EXISTS:-0}" == "1" ]] && printf 'v%s\n' "$REVA_TEST_VERSION_NAME"
        ;;
    "ls-remote --exit-code --tags origin refs/tags/v${REVA_TEST_VERSION_NAME}")
        [[ "${REVA_TEST_REMOTE_TAG_EXISTS:-0}" == "1" ]] && exit 0
        exit 2
        ;;
    "fetch origin refs/tags/v${REVA_TEST_VERSION_NAME}:refs/tags/v${REVA_TEST_VERSION_NAME}") ;;
    "tag -a v${REVA_TEST_VERSION_NAME} -m Reva Health Exporter v${REVA_TEST_VERSION_NAME}") ;;
    "push origin refs/tags/v${REVA_TEST_VERSION_NAME}") ;;
    *) printf 'Unexpected git command: %s\n' "$*" >&2; exit 64 ;;
esac
EOF

cat >"$fake_bin/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'gh %s\n' "$*" >>"$REVA_TEST_COMMAND_LOG"
case "$1 $2" in
    "auth status") ;;
    "run list") printf '24680\n' ;;
    "run watch") ;;
    "release download")
        while (($#)); do
            if [[ "$1" == "--dir" ]]; then
                shift
                mkdir -p "$1"
                : >"$1/reva-health-exporter-v${REVA_TEST_VERSION_NAME}.apk"
                exit 0
            fi
            shift
        done
        exit 64
        ;;
    *) printf 'Unexpected gh command: %s\n' "$*" >&2; exit 64 ;;
esac
EOF

cat >"$fake_bin/adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'adb %s\n' "$*" >>"$REVA_TEST_COMMAND_LOG"
case "$*" in
    "devices -l")
        printf 'List of devices attached\n'
        case "${REVA_TEST_ADB_DEVICES_MODE:-single}" in
            single)
                printf '198.51.100.42:40239 device product:lisa_ru model:2109119DG device:lisa transport_id:31\n'
                printf 'adb-1a1fc0ee-iyHqxS._adb-tls-connect._tcp device product:lisa_ru model:2109119DG device:lisa transport_id:1\n'
                ;;
            none)
                printf '198.51.100.42:40239 offline product:lisa_ru model:2109119DG device:lisa transport_id:31\n'
                ;;
            recovery)
                if [[ -f "$REVA_TEST_ADB_STATE_FILE" ]]; then
                    printf '198.51.100.42:40239 device product:lisa_ru model:2109119DG device:lisa transport_id:31\n'
                fi
                ;;
            multiple)
                printf '198.51.100.42:40239 device product:lisa_ru model:2109119DG device:lisa transport_id:31\n'
                printf '203.0.113.8:45555 device product:other model:other device:other transport_id:32\n'
                ;;
        esac
        ;;
    "pair 198.51.100.42:37123")
        read -r pairing_code
        [[ "$pairing_code" == "123456" ]]
        printf 'Successfully paired to 198.51.100.42:37123\n'
        ;;
    "connect 198.51.100.42:40239")
        [[ -z "${REVA_TEST_ADB_STATE_FILE:-}" ]] || : >"$REVA_TEST_ADB_STATE_FILE"
        printf 'already connected to 198.51.100.42:40239\n'
        ;;
    "-s 198.51.100.42:40239 get-state") printf 'device\n' ;;
    "-s 198.51.100.42:40239 install -r "*)
        if [[ "${REVA_TEST_INSTALL_FAIL:-0}" == "1" ]]; then
            printf 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]\n' >&2
            exit 1
        fi
        printf 'Success\n'
        ;;
    "-s 198.51.100.42:40239 uninstall dev.reva.healthexporter") printf 'Success\n' ;;
    "-s 198.51.100.42:40239 install "*) printf 'Success\n' ;;
    "-s 198.51.100.42:40239 shell dumpsys package dev.reva.healthexporter")
        printf 'versionCode=%s minSdk=30 targetSdk=36\nversionName=%s\n' \
            "$REVA_TEST_VERSION_CODE" "$REVA_TEST_VERSION_NAME"
        ;;
    "-s 198.51.100.42:40239 shell am force-stop dev.reva.healthexporter") ;;
    "-s 198.51.100.42:40239 shell am start -W -n dev.reva.healthexporter/.MainActivity")
        printf 'Status: ok\n'
        ;;
    *) printf 'Unexpected adb command: %s\n' "$*" >&2; exit 64 ;;
esac
EOF

chmod +x "$fake_bin/git" "$fake_bin/gh" "$fake_bin/adb"

output=$(
    PATH="$fake_bin:$PATH" \
    REVA_RELEASE_DOWNLOAD_DIR="$download_dir" \
    REVA_RELEASE_POLL_SECONDS=0 \
    REVA_TEST_COMMAND_LOG="$command_log" \
        "$script_under_test"
)

grep -Fq "git push origin refs/tags/v${test_version_name}" "$command_log"
grep -Fq 'gh run watch 24680 --compact --exit-status' "$command_log"
grep -Fq "gh release download v${test_version_name}" "$command_log"
grep -Fq 'adb devices -l' "$command_log"
grep -Fq 'adb connect 198.51.100.42:40239' "$command_log"
grep -Fq 'adb -s 198.51.100.42:40239 install -r' "$command_log"
grep -Fq "Installed and launched Reva Health Exporter v${test_version_name}" <<<"$output"

echo "release-to-device safe update test passed"

: >"$command_log"
rm -f "$device_state_file"
wireless_recovery_output=$(
    printf '198.51.100.42:37123\n123456\n198.51.100.42:40239\n' | env \
        PATH="$fake_bin:$PATH" \
        REVA_RELEASE_DOWNLOAD_DIR="$download_dir" \
        REVA_TEST_COMMAND_LOG="$command_log" \
        REVA_TEST_ADB_DEVICES_MODE=recovery \
        REVA_TEST_ADB_STATE_FILE="$device_state_file" \
            "$script_under_test" 2>&1
)

grep -Fq '1. Disconnect VPN on the mobile phone and this laptop.' <<<"$wireless_recovery_output"
grep -Fq '2. Go to Settings > Wireless debugging.' <<<"$wireless_recovery_output"
grep -Fq 'adb pair 198.51.100.42:37123' "$command_log"
grep -Fq 'adb connect 198.51.100.42:40239' "$command_log"
grep -Fq 'adb -s 198.51.100.42:40239 install -r' "$command_log"

echo "release-to-device wireless connection recovery test passed"

set +e
no_device_output=$(
    PATH="$fake_bin:$PATH" \
    REVA_RELEASE_DOWNLOAD_DIR="$download_dir" \
    REVA_TEST_COMMAND_LOG="$command_log" \
    REVA_TEST_ADB_DEVICES_MODE=none \
        "$script_under_test" 2>&1
)
no_device_status=$?
set -e

[[ $no_device_status -ne 0 ]]
grep -Fq 'No online Wi-Fi ADB device with an IP:port endpoint was found.' <<<"$no_device_output"

echo "release-to-device missing Wi-Fi device test passed"

set +e
multiple_devices_output=$(
    PATH="$fake_bin:$PATH" \
    REVA_RELEASE_DOWNLOAD_DIR="$download_dir" \
    REVA_TEST_COMMAND_LOG="$command_log" \
    REVA_TEST_ADB_DEVICES_MODE=multiple \
        "$script_under_test" 2>&1
)
multiple_devices_status=$?
set -e

[[ $multiple_devices_status -ne 0 ]]
grep -Fq 'Multiple online Wi-Fi ADB devices were found: 198.51.100.42:40239 203.0.113.8:45555 ' \
    <<<"$multiple_devices_output"

echo "release-to-device multiple Wi-Fi devices test passed"

: >"$command_log"
recovery_output=$(
    PATH="$fake_bin:$PATH" \
    REVA_RELEASE_DOWNLOAD_DIR="$download_dir" \
    REVA_RELEASE_POLL_SECONDS=0 \
    REVA_TEST_COMMAND_LOG="$command_log" \
    REVA_TEST_LOCAL_TAG_EXISTS=1 \
        "$script_under_test"
)

grep -Fq "git push origin refs/tags/v${test_version_name}" "$command_log"
grep -Fq "Pushing existing local tag v${test_version_name}" <<<"$recovery_output"

echo "release-to-device interrupted tag push recovery test passed"

: >"$command_log"
existing_release_output=$(
    PATH="$fake_bin:$PATH" \
    REVA_RELEASE_DOWNLOAD_DIR="$download_dir" \
    REVA_RELEASE_POLL_SECONDS=0 \
    REVA_TEST_COMMAND_LOG="$command_log" \
    REVA_TEST_REMOTE_TAG_EXISTS=1 \
        "$script_under_test"
)

grep -Fq "git fetch origin refs/tags/v${test_version_name}:refs/tags/v${test_version_name}" "$command_log"
if grep -Fq "git push origin refs/tags/v${test_version_name}" "$command_log"; then
    echo "An existing remote release tag was pushed again" >&2
    exit 1
fi
grep -Fq "Reusing existing remote tag v${test_version_name}" <<<"$existing_release_output"

echo "release-to-device existing remote release test passed"

: >"$command_log"
set +e
cancel_output=$(
    printf 'keep-data\n' | env \
        PATH="$fake_bin:$PATH" \
        REVA_RELEASE_DOWNLOAD_DIR="$download_dir" \
        REVA_RELEASE_POLL_SECONDS=0 \
        REVA_TEST_COMMAND_LOG="$command_log" \
        REVA_TEST_INSTALL_FAIL=1 \
            "$script_under_test" 2>&1
)
cancel_status=$?
set -e

[[ $cancel_status -ne 0 ]]
grep -Fq 'Reinstall cancelled; no app data was erased.' <<<"$cancel_output"
if grep -Fq 'adb -s 198.51.100.42:40239 uninstall' "$command_log"; then
    echo "The app was uninstalled without exact reinstall confirmation" >&2
    exit 1
fi

echo "release-to-device reinstall cancellation test passed"

: >"$command_log"
confirm_output=$(
    printf 'reinstall\n' | env \
        PATH="$fake_bin:$PATH" \
        REVA_RELEASE_DOWNLOAD_DIR="$download_dir" \
        REVA_RELEASE_POLL_SECONDS=0 \
        REVA_TEST_COMMAND_LOG="$command_log" \
        REVA_TEST_INSTALL_FAIL=1 \
            "$script_under_test" 2>&1
)

grep -Fq 'adb -s 198.51.100.42:40239 uninstall dev.reva.healthexporter' "$command_log"
grep -Fq 'adb -s 198.51.100.42:40239 install ' "$command_log"
grep -Fq "Installed and launched Reva Health Exporter v${test_version_name}" <<<"$confirm_output"

echo "release-to-device confirmed reinstall test passed"
