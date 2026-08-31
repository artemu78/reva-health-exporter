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

verify-schema-v1-fixtures() {
    ./gradlew testDebugUnitTest \
        --tests 'dev.reva.healthexporter.SchemaV1CompatibilityTest' \
        --tests 'dev.reva.healthexporter.ExportSchemaValidationTest'
}

verify-repository-privacy() {
    local forbidden_files
    forbidden_files=$(git ls-files | grep -E '\.(jks|keystore|p12|pfx|pem|key)$' || true)
    if [[ -n "$forbidden_files" ]]; then
        echo "Private key or keystore files are tracked:"
        echo "$forbidden_files"
        return 1
    fi

    local secret_matches
    local grep_status
    set +e
    secret_matches=$(git grep -nI -E -e \
        '-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----|AIza[0-9A-Za-z_-]{30,}|gh[pousr]_[0-9A-Za-z]{30,}' \
        -- .)
    grep_status=$?
    set -e
    if ((grep_status > 1)); then
        echo "Repository privacy scan could not run (git grep exit $grep_status)"
        return "$grep_status"
    fi
    if [[ -n "$secret_matches" ]]; then
        echo "Possible credential material is tracked:"
        echo "$secret_matches"
        return 1
    fi

    echo "Repository privacy scan passed (tracked files only)."
}

echo "[1/4] Fast automated suite"
./gradlew test lintDebug assembleDebug koverVerifyDebug

echo "[2/4] Schema version 1 fixtures"
verify-schema-v1-fixtures

echo "[3/4] Repository privacy scan"
verify-repository-privacy

echo "[4/4] Signed, minified release contract"
./scripts/verify-release-build.sh

echo "Automated release acceptance passed."
echo "Complete the physical Android 11 matrix in docs/issue-13-release-acceptance.md before release."
