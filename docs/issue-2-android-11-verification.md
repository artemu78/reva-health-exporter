# Issue 2 Android 11 verification

This protocol verifies the Health Connect provider and permission flow on the target Android 11 phone. Record only UI states and permission names. Do not capture health values, account identifiers, tokens, or raw diagnostics.

## Preconditions

- Install the issue 2 debug APK on the Android 11 phone.
- Install or update Health Connect from Google Play.
- Keep the phone offline from any export destination; issue 2 does not export data.

## Scenarios

### Grant

1. Clear the app's storage so no earlier permission-result notice remains.
2. Open Reva Health Exporter.
3. Confirm that Health Connect is reported as ready and all eight selected read permissions are listed as missing.
4. Tap **Grant read permissions** and grant every displayed permission.
5. Return to the app and confirm that all selected permissions are listed as granted, none are missing, and no permission action remains.

### Deny

1. Revoke the app's Health Connect permissions, then clear the app's storage.
2. Open the app and tap **Grant read permissions**.
3. Deny the request.
4. Confirm that the app remains open, explains that diagnostics are limited, lists granted and missing permissions, and offers **Try again**.

### Revoke

1. Grant all selected permissions and confirm the complete state in the app.
2. Open Health Connect settings and revoke one selected permission.
3. Return to the app.
4. Confirm that the revoked state is explained, the removed permission is listed as missing, the remaining permissions stay listed as granted, and **Grant again** is available.

## Sanitized evidence

| Scenario | Result | Evidence |
|---|---|---|
| Provider available | PASS | On the target Android 11 phone, the installed app displayed the permission action. |
| Grant all selected read permissions | RETEST REQUIRED | In build `849be1e`, tapping the permission action did not open Health Connect. The app immediately reported denial and Reva Health Exporter was absent from Health Connect app permissions. A regression fix added the Android 13-and-earlier provider registration. |
| Deny permission request | UNVERIFIED | Requires the target Android 11 phone. |
| Revoke one granted permission | UNVERIFIED | Requires the target Android 11 phone. |

Replace each `UNVERIFIED` entry only after performing the scenario on the target phone. Evidence should contain the app version, Android version, Health Connect version, scenario outcome, and a sanitized screenshot or written observation without health values.
