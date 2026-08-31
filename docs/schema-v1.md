# Reva Health Exporter Schema Version 1 (v1)

This document defines the canonical, versioned health-export data format for Reva Health Exporter.

---

## 1. Format selection & rationale: Standard uncompressed JSON

Reva Health Exporter exports health data batches in standard uncompressed **JSON** (`.json`) with MIME type `application/json`.

### Batch Structure: JSON Envelope

Each batch file is a standalone JSON document structured as:

```json
{
  "header": {
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
  },
  "records": [
    ...
  ]
}
```

### Deterministic Ordering
Records within `"records"` are sorted deterministically:
1. `startTime` ASC
2. `recordType` ASC
3. `endTime` ASC
4. `recordId` (if present) ASC

---

## 2. Stripped verbose metadata & privacy

To minimize payload clutter and produce clean, human-readable health exports, internal device provenance and sync metadata are stripped from exported records:

- **Stripped fields:**
  - `device` (including `manufacturer`, `model`, `type`)
  - `recordId`
  - `clientRecordId`
  - `recordingMethod`
  - `clientRecordVersion`
  - `lastModifiedTime`

- **Retained essential fields:**
  - `recordType` (e.g. `"steps"`, `"heart_rate"`)
  - `origin` (e.g. `"com.mi.health"`)
  - `startTime`, `startZoneOffset`
  - `endTime`, `endZoneOffset`
  - Metric-specific payload fields (`count`, `samples`, `distanceMeters`, `energyKilocalories`, `title`, `notes`, `stages`, `exerciseType`, `segments`, `laps`, `beatsPerMinute`, `percentage`)

---

## 3. Timestamp & timezone semantics

1. **Canonical time format:** All `startTime`, `endTime`, `createdAt`, and sample `time` values are represented as strict UTC ISO-8601 strings ending in `Z` (e.g. `"2026-08-30T10:00:00Z"`).
2. **Local context preservation:** When available from the Health Connect provider or wearable, the source timezone offset is preserved in `startZoneOffset` and `endZoneOffset` (e.g. `"+03:00"`, `"-05:00"`, `"Z"`). If unknown or not reported, the field is omitted or `null`.
3. **Interval invariants:** For all interval records, `startTime <= endTime`.

---

## 4. Common record format

Every canonical record contains standard timing and origin metadata:

```json
{
  "recordType": "steps",
  "origin": "com.mi.health",
  "startTime": "2026-08-29T08:00:00Z",
  "startZoneOffset": "+03:00",
  "endTime": "2026-08-29T08:15:00Z",
  "endZoneOffset": "+03:00",
  "count": 1250
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `recordType` | String | Yes | Canonical type identifier (`steps`, `heart_rate`, `distance`, `total_calories_burned`, `sleep_session`, `exercise_session`, `resting_heart_rate`, `oxygen_saturation`). |
| `origin` | String | Yes | Android package name of the app that inserted the record (e.g. `com.mi.health`). |
| `startTime` | String | Yes | UTC ISO-8601 start timestamp. |
| `startZoneOffset` | String | No | Zone offset at start (e.g. `"+03:00"`). |
| `endTime` | String | Yes | UTC ISO-8601 end timestamp. |
| `endZoneOffset` | String | No | Zone offset at end (e.g. `"+03:00"`). |

---

## 5. Record type definitions & canonical units

### 5.1. Steps (`recordType: "steps"`)
- **Canonical unit:** Integer count (non-negative).
```json
{
  "recordType": "steps",
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

### 5.7. Additional Supported Types (Optional per Batch)
- `resting_heart_rate`: `"beatsPerMinute": 58` (valid range `1..300`)
- `oxygen_saturation`: `"percentage": 98.5` (valid range `0.0..100.0`)

---

## 6. Validation and error handling

Any batch or record failing the schema invariants throws a specific `InvalidExportSchemaException`:
- Non-matching schema version.
- Mismatched `recordCount` or `recordTypes` between header and record items.
- Missing required fields or blank origin package.
- Inverted timestamps (`startTime > endTime` or sample timestamps outside record interval).
- Out-of-range numeric values (negative counts, negative distances, negative energy, non-positive heart rate).
- Invalid ISO-8601 formatting or invalid timezone offset formats.

---

## 7. Schema evolution & compatibility guarantees

1. **Frozen v1 Fixtures:** Compatibility tests are maintained against committed JSON and legacy NDJSON fixtures (`v1_golden_batch.json`, `v1_empty_batch.json`, `v1_golden_batch.ndjson`).
2. **Additive Non-Breaking Changes:** Adding optional fields to canonical records in v1 is permitted if parsers safely ignore or default them.
3. **Breaking Schema Changes:** Any breaking change requires bumping `schemaVersion` and providing explicit migration adapters.

