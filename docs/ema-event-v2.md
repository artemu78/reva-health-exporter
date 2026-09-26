# Reva EMA Event File Protocol v2

Version 2 keeps the identity, timestamps, lifecycle, storage, retry, and upsert rules from
[v1](ema-event-v1.md), but allows a submitted check-in to contain any meaningful subset of the
four scales and activity.

The normative schema is [`ema-event-v2.schema.json`](ema-event-v2.schema.json). Consumers must
continue to read v1 events; v1 answered events still require all four scales and an activity.

## Answered events

An answered v2 event must contain `answeredAt` and at least one of `mood`, `energy`, `focus`,
`stress`, or `activity`. Each present scale is an integer from 1 through 5. Missing scales and a
missing activity are omitted; they are not zero, empty strings, or synthetic “not set” values.
`activityLabel` may appear only with `activity`. A note remains optional and does not make an
otherwise empty check-in answered.

Pending, dismissed, and expired events contain none of the answer, activity, note, or answered-time
fields. Event IDs remain stable across status changes and consumers continue to upsert by `id`.

```json
{
  "schemaVersion": 2,
  "id": "018f-partial-event-001",
  "scheduleDate": "2026-09-26",
  "scheduledAt": "2026-09-26T09:15:30.123Z",
  "answeredAt": "2026-09-26T09:17:02.456Z",
  "mood": 4,
  "note": "Synthetic partial response",
  "status": "answered",
  "timezone": "Europe/Moscow"
}
```
