#!/usr/bin/env bash
set -euo pipefail

project_dir=$(cd "$(dirname "$0")/.." && pwd)
cd "$project_dir"

package_name="dev.reva.healthexporter"
main_activity="$package_name/.MainActivity"
release_workflow="release.yml"
config_path=${REVA_RELEASE_CONFIG:-"$project_dir/local-device.properties"}
download_dir=${REVA_RELEASE_DOWNLOAD_DIR:-"$project_dir/build/releases"}
poll_seconds=${REVA_RELEASE_POLL_SECONDS:-2}
max_polls=${REVA_RELEASE_MAX_POLLS:-60}

fail() {
    echo "Error: $*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "Required command '$1' was not found."
}

read_property() {
    local property_name=$1
    local property_file=$2
    awk -F= -v name="$property_name" '$1 == name { print substr($0, index($0, "=") + 1) }' \
        "$property_file" | tr -d '\r'
}

validate_adb_target() {
    local target=$1
    [[ "$target" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}:[0-9]{1,5}$ ]] || return 1

    local address=${target%:*}
    local port=${target##*:}
    local first second third fourth
    IFS=. read -r first second third fourth <<<"$address"
    local octet
    for octet in "$first" "$second" "$third" "$fourth"; do
        ((10#$octet <= 255)) || return 1
    done
    ((10#$port >= 1 && 10#$port <= 65535))
}

find_adb() {
    if command -v adb >/dev/null 2>&1; then
        command -v adb
        return
    fi

    local android_sdk_dir=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
    if [[ -z "$android_sdk_dir" && -n "${HOME:-}" ]]; then
        android_sdk_dir="$HOME/Library/Android/sdk"
    fi
    if [[ -n "$android_sdk_dir" && -x "$android_sdk_dir/platform-tools/adb" ]]; then
        printf '%s\n' "$android_sdk_dir/platform-tools/adb"
        return
    fi

    fail "adb was not found in PATH or the Android SDK."
}

require_command git
require_command gh

[[ -f "$config_path" ]] || fail "Create $config_path with ADB_TARGET=<phone-ip>:<port>."
adb_target=$(read_property ADB_TARGET "$config_path")
validate_adb_target "$adb_target" || \
    fail "ADB_TARGET must contain a valid IPv4 address and port."

version_name=$(read_property VERSION_NAME "$project_dir/version.properties")
version_code=$(read_property VERSION_CODE "$project_dir/version.properties")
[[ "$version_name" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "VERSION_NAME is not semantic versioning."
[[ "$version_code" =~ ^[1-9][0-9]*$ ]] || fail "VERSION_CODE must be a positive integer."
release_tag="v$version_name"
apk_name="reva-health-exporter-$release_tag.apk"
apk_path="$download_dir/$apk_name"

[[ $(git branch --show-current) == "main" ]] || fail "Switch to main before releasing."
[[ -z $(git status --porcelain) ]] || fail "The worktree must be clean before releasing."
git fetch origin main
head_sha=$(git rev-parse HEAD)
origin_main_sha=$(git rev-parse origin/main)
[[ "$head_sha" == "$origin_main_sha" ]] || fail "Local main must exactly match origin/main."
gh auth status >/dev/null

fail_reused_version() {
    local tag_sha=$1
    fail "$release_tag already points to commit ${tag_sha:0:12}, but current main is ${head_sha:0:12}. New code was committed without a new app version. Bump both VERSION_CODE and VERSION_NAME in version.properties, commit and push that change, then rerun this script. Existing release tags are not moved."
}

if [[ -z $(git tag --list "$release_tag") ]]; then
    set +e
    git ls-remote --exit-code --tags origin "refs/tags/$release_tag" >/dev/null
    remote_tag_status=$?
    set -e
    if ((remote_tag_status == 0)); then
        git fetch origin "refs/tags/$release_tag:refs/tags/$release_tag"
        tag_sha=$(git rev-parse "$release_tag^{}")
        [[ "$tag_sha" == "$head_sha" ]] || fail_reused_version "$tag_sha"
        echo "Reusing existing remote tag $release_tag."
    elif ((remote_tag_status != 2)); then
        fail "Could not inspect remote tag $release_tag."
    else
        echo "Creating and pushing $release_tag from synchronized main."
        git tag -a "$release_tag" -m "Reva Health Exporter $release_tag"
        git push origin "refs/tags/$release_tag"
    fi
else
    tag_sha=$(git rev-parse "$release_tag^{}")
    [[ "$tag_sha" == "$head_sha" ]] || fail_reused_version "$tag_sha"
    set +e
    git ls-remote --exit-code --tags origin "refs/tags/$release_tag" >/dev/null
    remote_tag_status=$?
    set -e
    if ((remote_tag_status == 0)); then
        echo "Reusing existing local and remote tag $release_tag."
    elif ((remote_tag_status == 2)); then
        echo "Pushing existing local tag $release_tag after an interrupted release."
        git push origin "refs/tags/$release_tag"
    else
        fail "Could not inspect remote tag $release_tag."
    fi
fi

run_id=""
for ((poll=1; poll<=max_polls; poll++)); do
    run_id=$(gh run list \
        --workflow "$release_workflow" \
        --branch "$release_tag" \
        --event push \
        --commit "$head_sha" \
        --limit 20 \
        --json databaseId \
        --jq '.[0].databaseId // empty')
    [[ -n "$run_id" ]] && break
    sleep "$poll_seconds"
done
[[ -n "$run_id" ]] || fail "The Android Release workflow did not appear for $release_tag."

echo "Waiting for GitHub release build $run_id."
gh run watch "$run_id" --compact --exit-status

mkdir -p "$download_dir"
gh release download "$release_tag" \
    --pattern "$apk_name" \
    --dir "$download_dir" \
    --clobber
[[ -f "$apk_path" ]] || fail "GitHub Release did not contain $apk_name."

adb=$(find_adb)
echo "Connecting to $adb_target over Wi-Fi."
"$adb" connect "$adb_target"
[[ $("$adb" -s "$adb_target" get-state) == "device" ]] || \
    fail "The configured Wi-Fi ADB target is not ready."

set +e
install_output=$("$adb" -s "$adb_target" install -r "$apk_path" 2>&1)
install_status=$?
set -e
printf '%s\n' "$install_output"

if ((install_status != 0)); then
    echo "The update failed. A clean reinstall erases this app's local state." >&2
    read -r -p "Type reinstall to uninstall the app and install it again: " confirmation
    [[ "$confirmation" == "reinstall" ]] || fail "Reinstall cancelled; no app data was erased."
    "$adb" -s "$adb_target" uninstall "$package_name"
    "$adb" -s "$adb_target" install "$apk_path"
fi

package_dump=$("$adb" -s "$adb_target" shell dumpsys package "$package_name")
installed_version_name=$(sed -n 's/.*versionName=\([^[:space:]]*\).*/\1/p' <<<"$package_dump" | head -1)
installed_version_code=$(sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' <<<"$package_dump" | head -1)
[[ "$installed_version_name" == "$version_name" ]] || \
    fail "Installed version name is $installed_version_name, expected $version_name."
[[ "$installed_version_code" == "$version_code" ]] || \
    fail "Installed version code is $installed_version_code, expected $version_code."

"$adb" -s "$adb_target" shell am force-stop "$package_name"
"$adb" -s "$adb_target" shell am start -W -n "$main_activity" >/dev/null

echo "Installed and launched Reva Health Exporter $release_tag on $adb_target."
