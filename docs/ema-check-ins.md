# Experience-sampling check-ins

The app can collect brief subjective observations during the day. These observations are intentionally
separate from Health Connect records and algorithmic scores from other products.

## Prompt schedule

- The default active period is 09:00–22:00 in the phone's current timezone.
- The default is five prompts per day.
- Each day is divided into evenly spaced sampling regions, then each prompt is jittered within its
  region. This changes prompt times between days while preserving at least 60 minutes between prompts
  whenever the configured period can support it.
- WorkManager stores one delayed job per prompt and refreshes the next two days of the schedule every
  12 hours. Android may deliver deferrable work later than requested because of device power policy.
- A prompt expires two hours after its scheduled timestamp. It is not repeated.

The Settings screen can enable or disable notifications, change active hours, choose one to ten prompts
per day, and replace the activity choices. Changing settings expires the old pending schedule and creates
a new one without changing completed historical observations.

## Check-in record

Every scheduled prompt is persisted before its worker is enqueued. Records use schema version 1 and keep:

- a unique ID and logical schedule date;
- precise `scheduledAt` and optional `answeredAt` instants;
- the timezone used when the event was scheduled;
- numeric mood, energy, focus, and stress values from 1 through 5;
- a stable activity ID and optional note;
- `pending`, `answered`, `dismissed`, or `expired` status;
- an optional map reserved for future subjective questions.

The four scale meanings are displayed in the check-in screen and remain stable:

- mood: very bad to very good;
- energy: exhausted to highly energetic;
- focus: unable to concentrate to deeply focused;
- stress: completely relaxed to extremely stressed.

Android's notification delete intent records a swipe or clear as `dismissed` when the platform delivers
that callback. A notification that remains unanswered reaches `expired`. Opening the notification removes
it without changing the response status, so the full-screen form can still be submitted.

## Storage and privacy

Events are stored as individual versioned JSON files under the app's private internal files directory at
`ema/events/`. Updates replace only the file for that event. Clearing app storage or uninstalling the app
removes these observations.

EMA events are currently local-only and are not included in Health Connect or Google Drive daily snapshot
exports. Any later export must preserve them as a distinct subjective-observation collection rather than
reinterpreting or combining them with physiological or derived metrics.
