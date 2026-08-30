# Issue 11: Google Drive batch upload verification

Reva Health Exporter uploads immutable Gzip-compressed NDJSON batches to the user's visible Google Drive
under the `Reva Health Exporter/schema-v1/YYYY/MM/` folder hierarchy using the narrow `drive.file` scope.

Batches are uploaded with stable identities and Drive `appProperties` metadata (`batchId`, `installationId`, `schemaVersion`),
enabling deterministic duplicate detection and safe recovery from indeterminate network timeouts without creating duplicate batches.

## Automated evidence

Run:

```bash
./gradlew test lintDebug assembleDebug
```

The test suites in `GoogleDriveDestinationTest` and `HttpGoogleDriveGatewayTest` verify:

1. **Folder hierarchy contracts:**
   - Folder absent: automatically constructs the visible hierarchy `Reva Health Exporter/schema-v1/YYYY/MM/`.
   - Folder present: reuses existing folders without creating duplicate directory structures.
   - Duplicate folders: deterministically selects the oldest folder when duplicate remote folders exist.

2. **Upload outcome and error classification:**
   - Success: creates a compressed `.ndjson.gz` batch with `application/gzip` MIME type and stable `appProperties`.
   - Authorization failure (HTTP 401 / unauthenticated): treated as non-retryable terminal failure requiring user reconnection.
   - Forbidden access (HTTP 403): treated as non-retryable terminal failure.
   - Rate limit (HTTP 429 / HTTP 403 `rateLimitExceeded`): treated as retryable failure.
   - Transient server error (HTTP 500, 502, 503, 504): treated as retryable failure.
   - Timeout (socket/connection timeout): treated as retryable failure.

3. **Indeterminate success & idempotency:**
   - If an upload succeeds on the server but the response network connection drops, the subsequent retry
     discovers the already-present batch via `appProperties` (`batchId`) and filename, advancing the checkpoint
     without uploading duplicate files.
   - Repeatedly uploading the same batch produces exactly one logical batch in Drive.

4. **Schema validation & decompression:**
   - Downloaded binary batch payloads from Drive decompress cleanly and validate against Schema Version 1.

5. **Account isolation:**
   - Exports for Account A and Account B are strictly isolated; operations on Account A cannot write to or read Account B's Drive.

6. **ExportCoordinator integration:**
   - Checkpoint advances only after durable Drive success.
   - Network or authorization failure does not advance the checkpoint and preserves the pending batch for retry.

## Live verification protocol

Use two dedicated Google accounts and synthetic data only.

1. Connect Google Drive with Account A in the app.
2. Trigger an export. Open Google Drive in a web browser or mobile client and verify the folder hierarchy:
   ```text
   Reva Health Exporter/
     schema-v1/
       2026/
         08/
           2026-08-29T000000Z--2026-08-30T000000Z--<batch-id>.ndjson.gz
   ```
3. Download the `.ndjson.gz` file, decompress it, and verify that:
   - Line 1 contains the valid batch header (`recordType: "header"`, `schemaVersion: 1`).
   - Subsequent lines contain sorted canonical health records.
4. Simulate a retry by triggering an export for the same window and confirm no duplicate file is created in the month folder.
5. Simulate an offline export by enabling Airplane Mode: confirm the batch remains pending locally and checkpoint does not advance.
6. Disable Airplane Mode, retry export: confirm successful upload and checkpoint advancement.
7. Disconnect Account A and connect Account B: trigger an export and confirm the file is placed only in Account B's Drive and Account A's Drive remains untouched.

The live two-account and physical network-drop checks remain `UNVERIFIED` until executed against live Google accounts on the target phone.

## Repository credential scan

Run the scan below to ensure no credentials, tokens, or private secrets exist in the repository:

```bash
rg -n -i 'client_secret|access_token|refresh_token|AIza[0-9A-Za-z_-]{30,}' \
  --glob '!**/build/**' --glob '!docs/issue-10-google-drive-authorization.md' --glob '!docs/issue-11-google-drive-upload.md' .
```
