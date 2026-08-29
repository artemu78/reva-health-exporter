# Reva Health Exporter

Reva Health Exporter is a small private Android 11 app for inspecting and exporting health data from a Xiaomi Smart Band 9.

```text
Smart Band 9 → Xiaomi Mi Fitness → Health Connect → Reva Health Exporter
                                                    ├─ local files
                                                    └─ Google Drive
```

The app does not connect to the band directly. It reads only records that Mi Fitness publishes through Health Connect and that the user explicitly permits it to access.

## Why diagnostic first?

Mi Fitness may expose only part of the band's data through Health Connect. The first version will therefore identify available record types, sources, history, and granularity on the target phone. Background upload will be built only after the required data is confirmed.

## Intended export flow

The first remote destination will be a visible folder in the user's own Google Drive. Exports will use versioned, compressed, immutable batches designed for safe retries. A configurable HTTPS destination may be added later if server-side processing becomes necessary.

## Privacy

- Health data stays on the phone until the user enables an export.
- Google Drive exports belong to the signed-in user; the project does not operate a central health-data store.
- Tests and repository fixtures use synthetic records only.
- Credentials, tokens, and personal health exports must never be committed or logged.

## Current status

The project is currently in the planning and diagnostic-design phase. There is no installable APK yet. Work is organized into small, test-first issues, with one branch and pull request per issue.

Start here:

- [Architecture](ARCHITECTURE.md)
- [Implementation roadmap](ROADMAP.md)
- [Testing strategy](TESTING.md)
- [Open issues](https://github.com/artemu78/reva-health-exporter/issues)
