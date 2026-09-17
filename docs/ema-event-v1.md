# Reva EMA Event File Protocol v1

This document defines the file format produced by Reva Health Exporter's experience-sampling
(EMA) feature. It is intended for programs that preserve, synchronize, or analyze raw subjective
observations.

This protocol is separate from the Health Connect export schema. A consumer must not reinterpret
EMA energy, mood, focus, or stress as physiological measurements or third-party derived scores.

The normative machine-readable schema is
[`ema-event-v1.schema.json`](ema-event-v1.schema.json).

## 1. File and transport rules

| Property | Rule |
|---|---|
| Encoding | UTF-8 JSON |
| Media type | `application/json` |
| Document shape | Exactly one JSON object per file |
| Filename | `<id>.json`, where `id` matches `[A-Za-z0-9_-]{1,100}` |
| Identity | The `id` field is the primary key; never use the filename or timestamp as a substitute |
| Update model | The producer atomically replaces the file for an event as its status changes |
| Ordering | Directory listing order has no meaning |
| Current location | App-private internal directory `ema/events/` |

The current app does not upload these files to Google Drive. Android app-private storage is not a
public interchange location. A future exporter may copy the same event objects into another
container, but it must preserve event identity and subjective semantics.

## 2. Consumer algorithm

For every readable `*.json` file:

1. Parse it as one JSON object.
2. Select the parser by `schemaVersion`. This document covers only version `1`.
3. Validate the status-dependent required fields.
4. Upsert the event by `id`; a later copy of the same ID replaces the earlier state.
5. Use `scheduledAt` to align the requested sampling moment with other time series.
6. Use `answeredAt` only to calculate response latency or the actual answer time.
7. Preserve unknown fields and ignore them when their meaning is not understood.
8. Report malformed or unsupported files as unavailable; do not convert them into zero-valued
   observations.

Consumers must not count different revisions of one `id` as multiple observations. Consumers also
must not convert `dismissed` or `expired` into numeric mood, energy, focus, or stress values.

## 3. Common fields

| Field | Type | Required | Meaning |
|---|---|---|---|
| `schemaVersion` | Integer | Yes | Protocol version. Exactly `1` for this specification. |
| `id` | String | Yes | Stable event identity and upsert key. |
| `scheduleDate` | `YYYY-MM-DD` string | Yes | Local date whose schedule generated the event. For an overnight active period, this can differ from the local calendar date of `scheduledAt`. |
| `scheduledAt` | UTC RFC 3339 timestamp | Yes | Intended sampling moment, emitted as an ISO-8601 instant ending in `Z`; fractional seconds may be present. |
| `status` | String enum | Yes | `pending`, `answered`, `dismissed`, or `expired`. |
| `timezone` | String | Yes | Zone ID used when scheduling, normally an IANA identifier such as `Europe/Moscow`; `UTC` is valid. |

Timestamps represent instants. Consumers must parse them as timezone-aware values and must not
interpret a trailing `Z` as the user's local timezone. `timezone` preserves the scheduling context.

## 4. Status lifecycle

The only v1 transitions are:

```text
pending ──► answered
        ├─► dismissed
        └─► expired
```

Terminal states never transition again.

| Status | Meaning |
|---|---|
| `pending` | The prompt was scheduled and has no terminal response yet. |
| `answered` | The user submitted all four scales and an activity. |
| `dismissed` | Android delivered an explicit notification-dismissal callback, or the user selected Skip. |
| `expired` | The response window ended without an answer, or pending work was invalidated by disabling/reconfiguring notifications. |

`dismissed` is observed only when Android delivers that signal. `expired` is therefore the safe
category for other unanswered events; it does not prove that the user saw the notification.

## 5. Answered fields

The following fields are required exactly when `status` is `answered` and absent otherwise:

| Field | Type | Range | Meaning |
|---|---|---|---|
| `answeredAt` | UTC RFC 3339 timestamp | — | Submission time. |
| `mood` | Integer | 1–5 | `1` very bad; `5` very good. |
| `energy` | Integer | 1–5 | `1` exhausted; `5` highly energetic. This is self-reported energy. |
| `focus` | Integer | 1–5 | `1` unable to concentrate; `5` deeply focused. |
| `stress` | Integer | 1–5 | `1` completely relaxed; `5` extremely stressed. |
| `activity` | String | Non-empty | Opaque configurable category ID, such as `work_coding`. |
| `activityLabel` | String | Non-empty | Optional snapshot of the human-readable activity label, such as `Work / coding`. New producers include it; older v1 files may omit it. |
| `note` | String | 1–280 characters | Optional user-entered note. Blank notes are omitted. |
| `additionalAnswers` | Object | Integer values | Optional extension map for new subjective dimensions. Keys cannot reuse the four core field names. |

Activity IDs are not a closed enum. Users can change the activity list, so consumers should store
the ID verbatim and must tolerate previously unseen IDs. When `activityLabel` is present, it is the
label shown at answer time and makes the event self-describing. Consumers must retain the ID as the
identity and treat the label as presentation metadata because labels can change later.

## 6. Examples

### Pending

```json
{
  "schemaVersion": 1,
  "id": "018f-example-event-001",
  "scheduleDate": "2026-09-15",
  "scheduledAt": "2026-09-15T09:15:30.123Z",
  "status": "pending",
  "timezone": "Europe/Moscow"
}
```

### Answered

```json
{
  "schemaVersion": 1,
  "id": "018f-example-event-001",
  "scheduleDate": "2026-09-15",
  "scheduledAt": "2026-09-15T09:15:30.123Z",
  "answeredAt": "2026-09-15T09:17:02.456Z",
  "mood": 4,
  "energy": 2,
  "focus": 3,
  "stress": 4,
  "additionalAnswers": {
    "calmness": 5
  },
  "activity": "work_coding",
  "activityLabel": "Work / coding",
  "note": "Finishing a focused task",
  "status": "answered",
  "timezone": "Europe/Moscow"
}
```

### Dismissed

```json
{
  "schemaVersion": 1,
  "id": "018f-example-event-002",
  "scheduleDate": "2026-09-15",
  "scheduledAt": "2026-09-15T12:04:00Z",
  "status": "dismissed",
  "timezone": "Europe/Moscow"
}
```

An expired event has the same shape as the dismissed example with `"status": "expired"`.

## 7. Compatibility rules

- Adding optional fields is backward-compatible within v1. Consumers must ignore unknown fields.
- Removing a required field, changing a field's type or meaning, changing scale direction, or
  changing identity/update semantics requires a new `schemaVersion`.
- A consumer that does not support the declared version must retain or quarantine the file rather
  than guessing its meaning.
- Producers should retain one golden fixture per supported version. The v1 answered fixture is
  `app/src/test/resources/fixtures/ema_v1/answered.json`.
- Aggregation and interpretation are outside this protocol. Store raw events first; derive daily
  scores or labels only in an analytics layer that records its own method and version.
