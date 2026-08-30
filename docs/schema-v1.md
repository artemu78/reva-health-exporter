# Reva Health Exporter Schema Version 1 (v1)

This document defines the canonical, versioned health-export data format for Reva Health Exporter.

---

## 1. Format selection & rationale: NDJSON + Gzip

Reva Health Exporter exports health data batches in **Newline-Delimited JSON (NDJSON)**, compressed with standard **Gzip** (`.ndjson.gz`).

### Trade-off analysis: NDJSON vs Single JSON Envelope

| Feature | Single Large JSON Envelope | NDJSON (`.ndjson`) + Gzip |
|---|---|---|
| **Memory footprint on mobile** | Entire batch (potentially 100,000+ data points) must reside in RAM simultaneously as a JSON DOM tree. | Streamable line-by-line reading and writing with fixed $O(1)$ memory usage. |
| **Recovery from partial corruption** | Any truncation or corrupted byte invalidates the entire file. | Lines prior to a corrupted line remain independently readable and parsable. |
| **Compression efficiency** | High compression ratio with Gzip. | Extremely high compression ratio with Gzip because repeated JSON field keys across adjacent lines compress down to negligible overhead. |
| **Batch metadata availability** | Usually at top or bottom of JSON object. | Line 1 is the immutable batch manifest/header (`recordType: "header"`), allowing instant metadata inspection without buffering whole payloads. |
| **External analysis tools** | Requires loading full JSON in pandas, DuckDB, BigQuery, or jq. | Native, ultra-fast streaming in `jq`, `duckdb`, `spark`, Python generators, and Unix tools (`zcat`, `grep`, `wc -l`). |

### Decision
Batches are serialized as UTF-8 Newline-Delimited JSON (`.ndjson`), optionally compressed with standard Gzip (`.ndjson.gz`).

---

## 2. Batch structure & envelope semantics

An export batch consists of:
1. **Line 1: Batch Header (Manifest):** A JSON object containing schema version, installation ID, batch ID, creation time, covered time window, record count, and distinct record types.
2. **Lines 2..N: Canonical Health Records:** Exactly one compact JSON object per line, sorted deterministically by `startTime` ASC, then `recordType` ASC, then `recordId` ASC.

### Batch Header (`recordType: "header"`)

```json
{
  "recordType": "header",
  "schemaVersion": 1,
  "installationId": "00000000-0000-4000-8000-000000000001",
  "batchId": "11111111-1111-4111-8111-111111111111",
  "createdAt": "2026-08-30T12:00:00Z",
  "timeWindow": {
    "startInclusive": "2026-08-29T00:00:00Z",
    "endExclusive": "2026-08-30T00:00:00Z"
  },
  "recordCount": 6,
  "recordTypes": [
    "distance",
    "exercise_session",
    "heart_rate",
    "sleep_session",
    "steps",
    "total_calories_burned"
  ]
}
```

#### Batch Header fields

| Field | Type | Description |
|---|---|---|
| `recordType` | String | Must always equal `"header"`. |
| `schemaVersion` | Integer | Version of the schema format (currently `1`). |
| `installationId` | String | Pseudonymous client installation UUID generated upon app installation. |
| `batchId` | String | Unique UUID identifying this immutable export batch. Retried uploads reuse the same `batchId`. |
| `createdAt` | String (ISO-8601 UTC) | Timestamp when the batch was assembled on the phone. |
| `timeWindow.startInclusive` | String (ISO-8601 UTC) | Lower bound of the Health Connect query interval (inclusive). |
| `timeWindow.endExclusive` | String (ISO-8601 UTC) | Upper bound of the Health Connect query interval (exclusive). Must be strictly after `startInclusive`. |
| `recordCount` | Integer | Exact count of data records following the header line. |
| `recordTypes` | Array of Strings | Sorted, distinct list of record types present in the batch. |

---

## 3. Timestamp & timezone semantics

1. **Canonical time format:** All `startTime`, `endTime`, `lastModifiedTime`, `createdAt`, and sample `time` values are represented as strict UTC ISO-8601 strings ending in `Z` (e.g. `"2026-08-30T10:00:00Z"`).
2. **Local context preservation:** When available from the Health Connect provider or wearable, the source timezone offset is preserved in `startZoneOffset` and `endZoneOffset` (e.g. `"+03:00"`, `"-05:00"`, `"Z"`). If unknown or not reported by the companion app, the field is `null` (or omitted).
3. **Interval invariants:** For all interval records, `startTime <= endTime`.

---

## 4. Common record metadata

Every canonical record contains standard provenance and platform metadata:

```json
{
  "recordType": "steps",
  "recordId": "rec_steps_golden_01",
  "origin": "com.mi.health",
  "startTime": "2026-08-29T08:00:00Z",
  "startZoneOffset": "+03:00",
  "endTime": "2026-08-29T08:15:00Z",
  "endZoneOffset": "+03:00",
  "clientRecordId": "client_steps_01",
  "clientRecordVersion": 1,
  "recordingMethod": 1,
  "device": {
    "manufacturer": "Xiaomi",
    "model": "Smart Band 9",
    "type": 6
  },
  "lastModifiedTime": "2026-08-29T08:15:02Z"
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `recordType` | String | Yes | Canonical type identifier (`steps`, `heart_rate`, `distance`, `total_calories_burned`, `sleep_session`, `exercise_session`, `resting_heart_rate`, `oxygen_saturation`). |
| `recordId` | String | Yes | Unique ID assigned by Health Connect or source client. |
| `origin` | String | Yes | Android package name of the app that inserted the record (e.g. `com.mi.health`). |
| `startTime` | String | Yes | UTC ISO-8601 start timestamp. |
| `startZoneOffset` | String | No | Zone offset at start (e.g. `"+03:00"`). |
| `endTime` | String | Yes | UTC ISO-8601 end timestamp. |
| `endZoneOffset` | String | No | Zone offset at end (e.g. `"+03:00"`). |
| `clientRecordId` | String | No | Client-provided record ID from companion app. |
| `clientRecordVersion` | Long | No | Client-provided record version. |
| `recordingMethod` | Integer | No | Health Connect recording method (`1` = actively recorded, `2` = automatically recorded, `3` = manual entry). |
| `device` | Object | No | Device metadata (`manufacturer`, `model`, `type` where `2` = watch, `6` = fitness band). |
| `lastModifiedTime` | String | No | UTC ISO-8601 timestamp of last modification in Health Connect. |

---

## 5. Record type definitions & canonical units

### 5.1. Steps (`recordType: "steps"`)
- **Canonical unit:** Integer count (non-negative).
```json
{
  "recordType": "steps",
  "recordId": "rec_steps_golden_01",
  "origin": "com.mi.health",
  "startTime": "2026-08-29T08:00:00Z",
  "startZoneOffset": "+03:00",
  "endTime": "2026-08-29T08:15:00Z",
  "endZoneOffset": "+03:00",
  "count": 1250
}
```

### 5.2. Heart Rate (`recordType: "heart_rate"`)
- **Canonical unit:** Beats per minute (`beatsPerMinute`: positive Long).
- **Structure:** Array of time-series samples within the record interval.
```json
{
  "recordType": "heart_rate",
  "recordId": "rec_hr_golden_01",
  "origin": "com.mi.health",
  "startTime": "2026-08-29T08:00:00Z",
  "startZoneOffset": "+03:00",
  "endTime": "2026-08-29T08:05:00Z",
  "endZoneOffset": "+03:00",
  "samples": [
    { "time": "2026-08-29T08:01:00Z", "beatsPerMinute": 72 },
    { "time": "2026-08-29T08:03:00Z", "beatsPerMinute": 78 }
  ]
}
```

### 5.3. Distance (`recordType: "distance"`)
- **Canonical unit:** Meters (`distanceMeters`: non-negative finite Double). All Health Connect length inputs (km, miles, etc.) are converted to meters.
```json
{
  "recordType": "distance",
  "recordId": "rec_dist_golden_01",
  "origin": "com.mi.health",
  "startTime": "2026-08-29T08:00:00Z",
  "startZoneOffset": "+03:00",
  "endTime": "2026-08-29T08:15:00Z",
  "endZoneOffset": "+03:00",
  "distanceMeters": 850.5
}
```

### 5.4. Total Calories Burned (`recordType: "total_calories_burned"`)
- **Canonical unit:** Kilocalories (`energyKilocalories`: non-negative finite Double). All Health Connect energy inputs (calories, joules) are converted to kilocalories.
```json
{
  "recordType": "total_calories_burned",
  "recordId": "rec_cal_golden_01",
  "origin": "com.mi.health",
  "startTime": "2026-08-29T08:00:00Z",
  "startZoneOffset": "+03:00",
  "endTime": "2026-08-29T08:15:00Z",
  "endZoneOffset": "+03:00",
  "energyKilocalories": 45.2
}
```

### 5.5. Sleep Session (`recordType: "sleep_session"`)
- **Structure:** Session interval with optional title/notes and structured stages (`1` = awake, `2` = sleeping, `3` = out of bed, `4` = light sleep, `5` = deep sleep, `6` = REM sleep).
```json
{
  "recordType": "sleep_session",
  "recordId": "rec_sleep_golden_01",
  "origin": "com.mi.health",
  "startTime": "2026-08-29T00:30:00Z",
  "startZoneOffset": "+03:00",
  "endTime": "2026-08-29T07:30:00Z",
  "endZoneOffset": "+03:00",
  "title": "Night Sleep",
  "stages": [
    { "startTime": "2026-08-29T00:30:00Z", "endTime": "2026-08-29T01:30:00Z", "stage": 4 },
    { "startTime": "2026-08-29T01:30:00Z", "endTime": "2026-08-29T03:30:00Z", "stage": 5 },
    { "startTime": "2026-08-29T03:30:00Z", "endTime": "2026-08-29T05:00:00Z", "stage": 6 }
  ]
}
```

### 5.6. Exercise Session (`recordType: "exercise_session"`)
- **Structure:** Workout session with `exerciseType` code (e.g. `79` for walking, `56` for running), optional title/notes, segments, and laps (with length in canonical meters).
```json
{
  "recordType": "exercise_session",
  "recordId": "rec_ex_golden_01",
  "origin": "com.mi.health",
  "startTime": "2026-08-29T18:00:00Z",
  "startZoneOffset": "+03:00",
  "endTime": "2026-08-29T18:45:00Z",
  "endZoneOffset": "+03:00",
  "exerciseType": 79,
  "title": "Evening Outdoor Walk",
  "segments": [
    { "startTime": "2026-08-29T18:00:00Z", "endTime": "2026-08-29T18:45:00Z", "segmentType": 64, "repetitions": 0 }
  ],
  "laps": [
    { "startTime": "2026-08-29T18:00:00Z", "endTime": "2026-08-29T18:22:30Z", "lengthMeters": 1500.0 },
    { "startTime": "2026-08-29T18:22:30Z", "endTime": "2026-08-29T18:45:00Z", "lengthMeters": 1500.0 }
  ]
}
```

### 5.7. Deferred / Optional Types
- `resting_heart_rate`: `"beatsPerMinute": 58`
- `oxygen_saturation`: `"percentage": 98.5` (range 0.0..100.0)

---

## 6. Validation and error handling

Any batch or record failing the schema invariants throws a specific `InvalidExportSchemaException`:
- Non-matching schema version.
- Mismatched `recordCount` or `recordTypes` between header and record lines.
- Missing required fields, blank IDs, or blank origin package.
- Inverted timestamps (`startTime > endTime` or sample timestamps outside record interval).
- Out-of-range numeric values (negative counts, negative distances, negative energy, non-positive heart rate).
- Invalid ISO-8601 formatting or invalid timezone offset formats.

---

## 7. Schema evolution & compatibility guarantees

1. **Frozen v1 Fixtures:** All future versions of Reva Health Exporter must retain automated compatibility tests against the committed `v1_golden_batch.ndjson` and `v1_golden_batch.ndjson.gz` fixtures.
2. **Additive Non-Breaking Changes:** Adding optional fields to canonical records in v1 is permitted if parsers safely ignore or default them.
3. **Breaking Schema Changes:** Any breaking change (modifying key semantics, changing canonical units, altering record ordering invariants) requires bumping `schemaVersion` to `2` and creating explicit migration/backward-compatibility adapters.
