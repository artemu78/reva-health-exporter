# Issue 10: Google Drive authorization verification

The app uses Google Identity Services for Android and requests only:

```text
https://www.googleapis.com/auth/drive.file
```

It does not request offline access, embed a client secret, or persist an access token. The retained
account key is a SHA-256 digest used to keep future destination state separated between accounts.

## Automated evidence

Run:

```bash
./gradlew test lintDebug assembleDebug
./gradlew connectedDebugAndroidTest
```

The coordinator suite covers authorization success, cancellation, denial, revocation, reconnect,
disconnect, account switch, and rejection of broader Drive scopes. The API 30 UI suite verifies that
authorization is not launched during activity startup and that connect, reconnect, and disconnect are
explicit button actions.

## Google Cloud configuration

Create an Android OAuth client for application ID `dev.reva.healthexporter`. Register one client entry
for each signing certificate used on a device (debug and release). Do not download or commit an OAuth
client-secret JSON file; an Android client does not need one in this application.

Sanitized setup evidence recorded on 2026-08-30:

- a dedicated **Reva Health Exporter** Google Cloud project exists;
- the Google Auth Platform audience is External and remains in testing mode;
- an Android OAuth client named **Reva Health Exporter debug** is registered for the stable application
  ID and the local debug signing certificate;
- Google Auth Platform Data Access contains exactly one application scope: `drive.file`;
- no client configuration file was downloaded or added to the repository.

The release signing certificate is `UNVERIFIED`: release credentials are intentionally unavailable in
the local workspace, and no signed release APK is currently published from which to inspect the public
certificate. Register a separate Android client entry when that certificate is available.

## Live verification protocol

Use two dedicated Google accounts and synthetic data only.

1. Install the APK signed with a fingerprint registered in the Google Cloud Android OAuth client.
2. Confirm that no authorization UI appears until **Connect Google Drive** is tapped.
3. Inspect the consent screen and confirm the requested Drive access is limited to files created or
   opened by Reva Health Exporter.
4. Cancel once and confirm local diagnostic snapshot export still works.
5. Connect account A, disconnect, revoke the grant in the Google account, and confirm the app moves to
   the reconnect-required state when Drive access is next checked.
6. Reconnect with account B and confirm the displayed state changes without retaining account A's
   destination association.
7. Run the repository credential scan below after setup.

```bash
rg -n -i 'client_secret|access_token|refresh_token|AIza[0-9A-Za-z_-]{30,}' \
  --glob '!**/build/**' --glob '!docs/issue-10-google-drive-authorization.md' .
```

The two-account phone test and revocation test remain `UNVERIFIED` until executed on the target Android
11 phone. Record only pass/fail states; do not capture account names, tokens, or personal Drive data.

## Physical-phone regression observed on 2026-08-30

On the target phone, authorization returned immediately without showing consent UI and the app displayed
the reconnect-required state. This is compatible with Google returning an already-granted narrow scope
without account identity, which the original code incorrectly treated as denied. Disconnect also
terminated the visible activity before a device crash trace could be captured.

The defensive correction accepts an already-granted `drive.file` result even when Google omits account
identity, while retaining a nullable account key so no destination can be associated with an unknown
account. Disconnect now waits for the asynchronous revocation result and converts both synchronous and
asynchronous failures to a recoverable reconnect-required state. Automated JVM and API 30 UI regression
tests cover both behaviors. Repeating both actions on the physical phone remains `UNVERIFIED`.
