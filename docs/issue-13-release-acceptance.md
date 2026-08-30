# Issue 13: Android 11 release acceptance

This is the reproducible acceptance protocol for the sideloadable MVP. Use only synthetic
test records in screenshots, fixtures, issue comments, and committed evidence. Never copy
health values, Google account identifiers, access tokens, signing credentials, or raw logs
into this repository.

## Automated release gate

From a clean checkout, with JDK 17 and Android SDK platform 36 installed, run:

```sh
./scripts/verify-release-acceptance.sh
```

The command runs the JVM suite, lint and debug assembly; independently validates schema-v1
fixtures; scans tracked files for key and credential material; creates an ephemeral test key;
builds a signed, minified release APK; and verifies its signature and manifest version. The
ephemeral APK proves the release build contract but is not the personal distribution build. It is
deleted automatically when the command finishes. Never install an APK from
`debug-fast-check-results`; GitHub-hosted runners use a non-permanent debug certificate. Use only
the `signed-release-candidate-v<version>` artifact for physical Google authorization checks.

CI additionally runs `connectedDebugAndroidTest` on an API 30 emulator. Record the clean CI
run URL and test counts below before release.

## Personal signed APK

Keep the keystore outside the repository and export these variables locally:

```sh
export ANDROID_KEYSTORE_PATH=/absolute/path/to/private-release.jks
export ANDROID_KEYSTORE_PASSWORD='<from password manager>'
export ANDROID_KEY_ALIAS='<release alias>'
export ANDROID_KEY_PASSWORD='<from password manager>'
./gradlew clean test lintRelease assembleRelease
```

Verify `app/build/outputs/apk/release/app-release.apk` with the newest installed Android SDK
build tools:

```sh
"$ANDROID_HOME/build-tools/<version>/apksigner" verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME/build-tools/<version>/aapt2" dump badging \
  app/build/outputs/apk/release/app-release.apk | head -1
```

Do not record certificate owner details if they contain personal information. Record only the
SHA-256 certificate digest, APK SHA-256 digest, version, and pass/fail result.

## Physical Android 11 matrix

Use the personal signed, minified APK for every row. Record timestamps, statuses, counts,
package names, file names, and hashes only. Inspect filtered logs for exceptions and state
transitions; do not attach unrestricted device logs.

| Journey | Procedure | Required result | Status |
|---|---|---|---|
| Clean install | Uninstall the app, install the signed APK, open it, grant selected Health Connect permissions, run diagnostics, create a local export, connect Drive, select **Export now**, then enable periodic export. | Every step completes; the downloaded gzip batch validates as schema v1. | UNVERIFIED |
| New Mi Fitness data | Synchronize Mi Fitness, note only the pre-export Drive file count, export, then note the new count and file metadata. | Exactly one new valid immutable batch covers the new time window. | UNVERIFIED |
| Upgrade | Install the previous APK signed by the same key, create pending synthetic state, then install the candidate with `adb install -r`. | App data remains readable; pending work and checkpoints remain safe. | UNVERIFIED |
| Permission revocation | Revoke a selected Health Connect permission before **Export now**. | User-action-required state; checkpoint does not advance; granting permission allows recovery. | UNVERIFIED |
| Drive revocation | Revoke Google access outside the app, then export. | Authorization loss is detected without retry loop; reconnecting resumes the pending batch. | UNVERIFIED |
| Offline recovery | Enable airplane mode before export, then restore connectivity and retry. | Stable pending batch identity; one Drive file; checkpoint advances only after success. | UNVERIFIED |
| Account switch | Export with test Account A, disconnect, connect test Account B, and export again. | Destination state is isolated; neither account can see the other's app-created batch through the app. | UNVERIFIED |
| Corrupt local state | Back up app state, inject a deliberately malformed checkpoint/pending-batch value using a debuggable synthetic build, then open/export. Repeat the observable recovery path with the release build without state injection. | Failure is visible and safe; no silent checkpoint advance or overwrite. | UNVERIFIED |
| Periodic export | With prerequisites satisfied, enqueue periodic export, background the app, and observe the next eligible execution. | Export follows device capability; Android 11 background-read limitation remains visible when unsupported. | UNVERIFIED |
| Delete | Disconnect Drive, delete the visible Drive folder/files, clear app storage, and uninstall. | Local state is removed; user-owned Drive data is deletable by the user. | UNVERIFIED |

## Artifact validation

For every local and Drive `.ndjson.gz` artifact, decompress it outside the app and validate it
with the schema-v1 parser/tests. Confirm header `schemaVersion` is `1`, `recordCount` matches
the record lines, timestamps are ordered, and batch identity is stable across retry. Record
only the file SHA-256, byte size, record count, type names, and validation result.

## Sanitized evidence record

- Commit SHA: UNVERIFIED
- CI run URL: UNVERIFIED
- JVM tests: UNVERIFIED
- API 30 instrumentation tests: UNVERIFIED
- Release APK version and SHA-256: UNVERIFIED
- Release certificate SHA-256: UNVERIFIED
- Schema-v1 artifacts validated: UNVERIFIED
- Repository/build/log privacy inspection: UNVERIFIED
- Physical matrix completed at: UNVERIFIED
- Known Mi Fitness findings: [compatibility report](mi-fitness-compatibility-report.md)

`UNVERIFIED` is not a pass or a failure. Replace each entry only with evidence observed during
the release run; do not infer live-service or physical-device success from automated tests.
