# Issue #82: EMA notification sound and creation time

## Behavior

- New channels explicitly use `Settings.System.DEFAULT_NOTIFICATION_URI` with notification audio
  usage and default importance. Android controls playback through sound, volume, silent mode, and
  Do Not Disturb settings; the app does not request a DND bypass.
- The existing `ema_check_ins` channel ID is retained. Re-registering it preserves existing sound
  and importance preferences. An already muted channel must be changed in Android settings.
- Notifications expose their actual creation instant through `when` and `EXTRA_SHOW_WHEN`, rather
  than displaying the earlier scheduled prompt time.
- Version: 0.1.33 (33).

## Automated evidence (2026-09-29)

Before the behavior change, `EmaNotificationTest.notificationShowsActualCreationTime` failed on
API 30. The sound and existing-silent-channel cases already passed with Android defaults.

Final command:

```sh
./gradlew test lintDebug assembleDebug koverVerifyDebug connectedDebugAndroidTest
```

- JVM: 323 passed, 0 failed, 0 skipped.
- Android 11 / API 30 ARM64 emulator: 56 passed, 0 failed, 0 skipped.
- Lint, debug APK assembly, and the debug coverage gate passed.
- Three new Android tests cover system-default sound and notification audio without DND bypass,
  visible creation timestamp, and preservation of an existing silent channel on repeated posting.
- Tests use synthetic event IDs and isolated channels. The notification assertion waits for Android's
  asynchronous posting before inspecting the delivered notification.

Physical-phone audible playback and OEM notification-shade presentation: **UNVERIFIED**. No phone
was connected. On-device acceptance remains to confirm an audible reminder in normal mode,
suppression under the user's silent/DND settings, and the displayed creation time. Emulator checks
establish configuration and timestamp metadata, not speaker output.

Android channel behavior reference:
https://developer.android.com/develop/ui/compose/notifications/channels

CI results are linked from the pull request for #82.
