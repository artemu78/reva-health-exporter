# Reva Health Exporter

Reva Health Exporter is a small private Android 11 app for inspecting and exporting health data from a Xiaomi Smart Band 9.

```text
Smart Band 9 → Xiaomi Mi Fitness → Health Connect → Reva Health Exporter
                                                    ├─ local files
                                                    └─ Google Drive
```

The app does not connect to the band directly. It reads only records that Mi Fitness publishes through Health Connect and that the user explicitly permits it to access.

## Why diagnostic first?

Mi Fitness may expose only part of the band's data through Health Connect. The app therefore keeps
diagnostics for identifying available record types, sources, history, and granularity on the target
phone. Local and Google Drive export plus WorkManager scheduling are implemented, but they can
export only the records that Health Connect actually makes available.

## Intended export flow

The first remote destination will be a visible folder in the user's own Google Drive. Exports will use versioned, compressed, immutable batches designed for safe retries. A configurable HTTPS destination may be added later if server-side processing becomes necessary.

## Privacy

- Health data stays on the phone until the user enables an export.
- Google Drive exports belong to the signed-in user; the project does not operate a central health-data store.
- Tests and repository fixtures use synthetic records only.
- Credentials, tokens, and personal health exports must never be committed or logged.

## Current status

The MVP implements diagnostics, schema-v1 local batches, Google Drive authorization/upload,
immediate export, and WorkManager scheduling. Xiaomi Mi Fitness compatibility remains dependent
on its version, region, phone, permissions, and synchronization state; see the linked compatibility
report before interpreting an empty result.

## Install and permissions

Download the signed APK from the matching GitHub Release and verify its SHA-256 digest against
the release evidence. On the Android 11 phone, allow installation from the app used to open the
APK, install it, and then disable that temporary installation permission if desired. A debug-signed
build cannot be upgraded to a release-signed build; uninstall it first. Later APKs must use the same
release key and a higher `VERSION_CODE` to preserve local app state during upgrade.

The app requests read access only for its maintained Health Connect candidate catalog. You can
grant a subset, deny access, or revoke it later in Health Connect. Google Drive connection is
separate and uses the narrow `drive.file` scope, which limits the app to files it created or that
the user explicitly opened with it. Revoked permission or authorization requires user action and
must not become an infinite background retry.

Android 11 Health Connect providers may not support background reads. In that case **Export now**
is the reliable path; periodic work cannot export data the provider refuses to expose in the
background. Mi Fitness may publish only a subset of band metrics and must synchronize before new
records can be exported.

## Export ownership and deletion

Local exports and the visible `Reva Health Exporter` folder in Google Drive belong to the user.
Disconnecting Drive stops app access but does not delete already exported files. Delete those files
or their folder in Drive, then empty Drive trash if permanent remote deletion is desired. To remove
on-device checkpoints, pending batches, authorization state, and the pseudonymous installation ID,
clear the app's storage or uninstall it. Keep any exported copies you still need before clearing data.

## Export history and manual backfill

The **Export history** section shows the latest 14 local calendar days using the phone's current
timezone. **Uploaded** means confirmed app-created Google Drive batch intervals cover the whole
day; **Partially uploaded** means only part is covered; **Not uploaded** means a successful Drive
refresh found no coverage; and **Pending / retrying** means a stable local batch still needs durable
confirmation. **Unknown** is deliberately different from missing: it appears while Drive is
disconnected, authorization has been revoked, refresh failed, or app-created metadata is incomplete.

Select one or more days that are not fully uploaded, tap **Upload selected days**, and confirm the
displayed range. The app reads only the missing local-day interval(s), uses the normal immutable and
duplicate-safe Drive batch flow, and leaves the incremental export checkpoint unchanged. **No
records found** describes Health Connect source availability for that interval; it does not mean a
Drive batch was uploaded. Drive coverage likewise proves only that an export interval was confirmed,
not that Mi Fitness supplied every expected measurement.

## Build and test

Use JDK 17 and an Android SDK containing platform 36. The Gradle wrapper downloads the supported Gradle version, so a separate Gradle installation is not required.

Run the fast checks used by CI:

```sh
./gradlew test lintDebug assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Run the launch test on an Android 11 (API 30) device or emulator:

```sh
./gradlew connectedDebugAndroidTest
```

GitHub Actions runs the fast checks, the 90% line/85% branch coverage gate for the critical pure
Kotlin export coordinator, and API 30 instrumentation in separate clean jobs. Build reports and
the debug APK are retained in the explicitly named `debug-fast-check-results` artifact. That debug
APK is not a Google-enabled release candidate because its hosted-runner signing certificate is not
stable. For device acceptance, download only the `signed-release-candidate-v<version>` artifact,
which is built from the repository's permanent signing secrets. Before a personal release, run the complete
[Android 11 acceptance protocol](docs/issue-13-release-acceptance.md).

## Versioned signed releases

Application versions live in `version.properties`:

```properties
VERSION_CODE=1
VERSION_NAME=0.1.0
```

`VERSION_CODE` must increase for every APK that should update an installed version. `VERSION_NAME` is the user-visible semantic version. A release tag must match it exactly with a `v` prefix; version `0.1.0` is released from tag `v0.1.0`.

Before tagging, merge the version change and all intended code into `main`, then run:

```sh
./gradlew printVersion
./gradlew verifyReleaseTag -PreleaseTag=v0.1.0
git tag -a v0.1.0 -m "Reva Health Exporter v0.1.0"
git push origin v0.1.0
```

The tag starts the Android Release workflow. It restores the private signing keystore from GitHub Actions secrets, runs tests and release lint, builds a signed and minified APK, verifies its signature, and attaches it directly to the matching GitHub Release.

### Release and install over Wi-Fi

After the intended pull request is merged, update local `main` so it exactly matches `origin/main`.
The release command uses the version already present in `version.properties`; it does not create a
version commit. Before running it, pair and connect the phone through Android's Wireless debugging
screen, then confirm that `adb devices -l` includes one online IPv4 endpoint:

```text
192.0.2.10:40239 device product:example model:example device:example transport_id:1
```

The port is assigned dynamically and may change when Wireless debugging restarts. The release
command reads the current endpoint from `adb devices -l`; it ignores the duplicate mDNS alias and
never falls back to a USB device. If more than one online IPv4 endpoint is present, disconnect the
devices not intended for the release before continuing.

If no online IPv4 endpoint is present, the command starts an interactive recovery flow. It asks you
to disconnect VPN on the phone and laptop, open Wireless debugging, optionally enter the pairing
IP:port and six-digit code, and finally enter the separate connection IP:port from the main screen.
After connecting, it reads `adb devices -l` again before continuing.

Then run:

```sh
./scripts/release-to-device.sh
```

The command checks clean synchronized `main`, creates or safely resumes the matching tag, waits for
the exact GitHub release workflow, downloads the permanently signed APK under `build/releases/`,
connects to the discovered phone, installs the update, verifies its version, and launches the app.
If Android rejects the update, the command explains that a clean reinstall erases local app state.
It uninstalls only if you type the exact lowercase word `reinstall`; any other response cancels.

The required repository secrets are `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. Keep an offline encrypted backup of the original keystore and its passwords; GitHub does not let you recover secret values later. Never commit a keystore or credentials.

The first release-signed APK cannot update a currently installed debug-signed APK. Uninstall the debug build once, accepting loss of this app's local state, and install the release APK. Later releases signed by the same key can update it normally.

Start here:

- [Architecture](ARCHITECTURE.md)
- [Implementation roadmap](ROADMAP.md)
- [Testing strategy](TESTING.md)
- [Mi Fitness compatibility report](docs/mi-fitness-compatibility-report.md)
- [Schema version 1 specification](docs/schema-v1.md)
- [Open issues](https://github.com/artemu78/reva-health-exporter/issues)
