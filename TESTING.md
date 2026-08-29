# Reva Health Exporter testing strategy

Testing is a primary product capability for this project. Health export failures can be silent: records may be skipped, duplicated, attributed to the wrong source, or marked exported before the destination has stored them. The test strategy therefore concentrates on observable behavior and data invariants rather than implementation details.

## Completion rule

An implementation issue is complete only when:

1. it was implemented in its own issue branch and submitted through its own pull request;
2. the pull request links the issue with `Closes #<number>`;
3. its required automated tests exist and pass;
4. the full fast test suite remains green;
5. Android-specific tests pass when the issue touches Android behavior;
6. required physical-device or live-service checks have recorded sanitized evidence;
7. every defect found while implementing the issue has a regression test;
8. no test or artifact contains personal health values or credentials;
9. all required repository checks pass before merge.

Manual testing never substitutes for an automatable check. Automated tests never substitute for the two irreducible external checks: actual Mi Fitness interoperability and actual Google authorization/Drive behavior.

## Test layers

| Layer | Runtime | Purpose | Expected frequency |
|---|---|---|---|
| Unit | Local JVM | Pure mapping, classification, time windows, schemas, batching, checkpoint transitions, retry decisions | Every commit |
| Component | Local JVM, usually with fakes | Health Connect probe, export pipeline, destination contracts, authorization coordinator | Every commit |
| Android feature | API 30 emulator | UI state, lifecycle, document picker boundary, manifest wiring, worker integration | Every pull request |
| Application | API 30 emulator and physical Android 11 phone | Cross-component user journeys and background execution | Before closing the relevant issue |
| Live integration | Physical Android 11 phone plus Mi Fitness or Drive test account | Behavior that cannot be faithfully reproduced locally | At explicit roadmap gates |
| Release candidate | Clean physical-device install of signed build | Complete MVP journey, upgrade/reinstall behavior, privacy inspection | Before release |

Most tests should run on the local JVM. Emulator and physical-device tests are fewer because they are slower and more fragile, but they cover the platform boundaries that local tests cannot prove.

## Required test seams

Keep these dependencies replaceable from the beginning:

- `HealthConnectClient` and feature/availability checks;
- permission state and interactive permission launcher;
- clock, timezone, and batch-ID generation;
- checkpoint and pending-batch persistence;
- file/document output;
- Google authorization and Drive operations;
- network availability and destination responses;
- WorkManager scheduling wrapper.

Use the official `FakeHealthConnectClient` for Health Connect unit tests. It supports inserted synthetic records, multiple responses, and exception stubs. Keep Drive behind a narrow project-owned gateway so tests do not depend on a production Google account.

## Coverage expectations

Coverage is evidence, not the definition of correctness. The project nevertheless enforces these minimum expectations once coverage reporting is configured:

- pure Kotlin core: at least 90% line and 85% branch coverage;
- checkpoint, deduplication, retry, and batch-identity state machines: every branch and transition covered;
- canonical record mappers: every supported type, optional-field case, and validation failure covered;
- availability, permission, worker-result, and error classification: every defined state covered;
- UI: every modeled screen state and user action covered by a semantic UI test;
- changed production branches require a test or an explicit written explanation of why the branch can only be verified on a physical device or live service.

Do not add low-value assertions solely to increase a percentage. Assert outputs, state transitions, persisted data, requests at external boundaries, and user-visible behavior.

## Core invariants

These must have direct automated tests and remain true throughout the project:

1. A checkpoint never advances before durable destination success.
2. Retrying a pending batch preserves its identity and logical contents.
3. A retry cannot create a second logical export for the same batch.
4. Records on time-window boundaries are neither lost nor exported indefinitely.
5. One failed record type does not erase successful diagnostic results for other types.
6. Permission or authorization loss becomes a user-action-required state rather than an infinite retry loop.
7. No background component attempts to launch interactive authorization.
8. Serialization is deterministic for the same canonical batch.
9. Logs and default diagnostics contain metadata and counts, not raw health values or tokens.
10. Data from one signed-in Google account is never associated with another account's destination state.

## Health Connect testing

Automated tests use synthetic records with `FakeHealthConnectClient` and cover:

- every candidate and confirmed record type;
- empty results and multiple data origins;
- multiple pages and a failure or expired token after an earlier successful page;
- permission missing or revoked before and during a workflow;
- provider unavailable or requiring update;
- feature available and unavailable;
- timestamps around midnight, daylight-saving changes, and exact range boundaries;
- cancellation and unexpected API exceptions.

The Health Connect Toolbox and the physical Android 11 phone are used to cross-check the real provider. Raw values from the user's phone must not be committed or attached to GitHub.

## Export and persistence testing

Use a fake destination and controllable persistence layer to inject failure at every meaningful point:

- before batch persistence;
- after batch persistence but before upload;
- during upload;
- after remote success but before the response reaches the app;
- after response receipt but before checkpoint update;
- during checkpoint update;
- after checkpoint update and before worker completion;
- during restart with a pending or partially processed batch;
- while two export triggers attempt to run concurrently.

Tests verify recovery, stable identity, no lost records, and no uncontrolled duplicates. Corrupt local state must fail safely and visibly rather than silently resetting progress.

## Google Drive testing

Most Drive behavior uses a fake gateway or controlled HTTP transport. Cover:

- folder absent, present once, and duplicated;
- successful create and upload;
- retry after timeout where the first upload may have succeeded;
- duplicate detection by stable ID or private app property;
- authorization cancellation and revocation;
- rate limiting, transient server errors, forbidden access, and invalid configuration;
- account switch and separation of per-account destination state;
- downloaded gzip content and schema validation.

Live tests use dedicated test accounts and synthetic batches. They verify the OAuth scope, visible folder layout, downloadability, idempotent retry, disconnect, reconnect, and account isolation. They are not run on every commit.

## WorkManager testing

Worker business logic is tested independently with injected fakes. WorkManager integration tests use its official testing helpers to simulate constraints and periodic intervals without sleeping.

Cover:

- success, retry, permanent failure, and user-action-required results;
- network and battery constraints;
- unique periodic scheduling;
- concurrent `Export now` and periodic triggers;
- cancellation;
- retry/backoff configuration;
- persistence across process recreation in automated tests where possible;
- persistence across device reboot as a physical-device check.

Tests must never wait for a real periodic interval.

## UI and lifecycle testing

Use semantic UI assertions rather than screenshots as the primary automated check. Cover every modeled state, navigation action, refresh, cancellation, and recoverable error. Add lifecycle tests for recreation and saved state. Screenshots are supplemental evidence, not pass/fail tests.

## CI gates

Once the Android project exists, CI must provide:

1. a fast job on every commit for local unit/component tests, lint, and debug assembly;
2. an API 30 emulator job for instrumented feature tests;
3. coverage reporting with failure thresholds for the pure Kotlin core;
4. schema and golden-fixture validation;
5. a check that committed fixtures contain no known credential patterns or real diagnostic exports;
6. uploaded test reports and APK artifacts when a job fails or produces a release candidate.

The exact Gradle task names are established in the scaffold issue and documented in the README. No issue may weaken or skip an existing test merely to make CI green without documenting and correcting the underlying cause.

## Evidence attached to each issue

Every completed issue should contain:

- the exact test commands executed;
- counts of passed, failed, and skipped tests;
- links to CI runs;
- the names of new test classes or scenarios;
- sanitized emulator or physical-device evidence when required;
- known gaps explicitly marked `UNVERIFIED` with the reason and a follow-up issue.

`UNVERIFIED` is not success, but it is also not an invented failure. It keeps the evidence boundary explicit.

## Official references

- [Android testing strategies](https://developer.android.com/training/testing/fundamentals/strategies)
- [Health Connect unit tests and `FakeHealthConnectClient`](https://developer.android.com/health-and-fitness/health-connect/test/unit-tests)
- [WorkManager integration testing](https://developer.android.com/develop/background-work/background-tasks/testing/persistent/integration-testing)
- [Testing WorkManager worker implementations](https://developer.android.com/develop/background-work/background-tasks/testing/persistent/worker-impl)
