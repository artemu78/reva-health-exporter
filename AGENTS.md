# Reva Health Exporter

## Project overview

This project is a small, private Android application for Android 11. It is intended to be built locally and sideloaded rather than published to an app store.

The app reads health data that Xiaomi Mi Fitness exposes through Health Connect for a Xiaomi Smart Band 9. It does not communicate with the band directly over Bluetooth and does not attempt to reverse-engineer Xiaomi protocols.

See [ARCHITECTURE.md](ARCHITECTURE.md) for the application structure and [ROADMAP.md](ROADMAP.md) for the dependency-ordered, verifiable implementation plan.

## Delivery phases

### 1. Diagnostic app

Build the smallest useful app first. It should:

- connect to Health Connect and request only the permissions needed for inspection;
- enumerate available Health Connect record types and contributing data sources;
- display or log recent records in a readable form;
- make it easy to determine which Smart Band 9 metrics Mi Fitness actually writes to Health Connect.

Do not build the full export pipeline until this diagnostic phase confirms that the required data is available.

### 2. Background exporter

After the available records and sources have been verified, add periodic export that:

- runs through Android WorkManager;
- reads records incrementally, avoiding duplicate exports where practical;
- serializes supported records to a simple, versioned JSON format;
- sends data over HTTPS to a configurable endpoint;
- records successful progress and retries transient failures safely;
- keeps credentials and other sensitive configuration out of source control and logs.

## Technical direction

- Prefer Kotlin.
- Target Android 11 compatibility.
- Use the official Health Connect SDK.
- Keep the Android architecture minimal: a small UI, a focused Health Connect data layer, and a WorkManager worker when exporting is introduced.
- Avoid unnecessary frameworks, abstractions, services, databases, and premature generalization.
- Keep the application private, local-first, and narrowly scoped to inspecting and exporting the user's own health data.

## Primary risk and validation rule

Mi Fitness may expose only a subset of Xiaomi Smart Band 9 metrics through Health Connect, and availability may vary by Mi Fitness version, device, region, permissions, or sync state. Treat Health Connect data availability as an unknown until verified on the target phone.

Before designing the uploader or promising support for specific metrics, use the diagnostic app to verify:

- which record types are present;
- which application or device is recorded as the data source;
- how recent, complete, and granular the records are;
- whether records remain accessible during background execution.

Missing Health Connect data is not evidence that the band failed to collect it. It may mean Mi Fitness does not publish that metric through Health Connect.

## Scope boundaries

- No direct Bluetooth integration with the band in the initial scope.
- No Xiaomi protocol reverse-engineering.
- No cloud dashboard or broad analytics platform.
- No app-store release work unless explicitly requested later.
- No expansion to other wearables until the Smart Band 9 flow is validated.
