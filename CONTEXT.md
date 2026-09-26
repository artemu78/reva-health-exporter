# Reva Health Exporter Context

Shared vocabulary for discussing the app, its data, and its user-facing behavior.

## Language

### External Systems and Sources

**Fitness bracelet**:
The Xiaomi Smart Band 9 worn by the user to collect health and activity measurements. Its data reaches this app through Mi Fitness and Health Connect; the exporter does not read the bracelet directly.
_Avoid_: direct band connection

**Mi Fitness app**:
Xiaomi's companion app for the fitness bracelet and a source of records published to Health Connect. Measurements visible in Mi Fitness are not necessarily available through Health Connect.

**Health Connect**:
Google's on-device health-data store through which this app reads records shared by source apps with the user's permission. It is distinct from both the fitness bracelet and the Google Fit app.
_Avoid_: Google Fit when referring to Health Connect

**Google Fit app**:
A separate source app whose distance and total-calories records in Health Connect are eligible for this project's exports. The exporter reads those records through Health Connect, not through a direct Google Fit connection.

**Google Drive storage**:
The user's cloud storage containing the app's visible export folder. It is the remote destination for uploaded data.
_Avoid_: health data source when referring to the upload destination

**Data source**:
The app identified as the origin of a Health Connect record, such as Mi Fitness or Google Fit. A record's source app and the device that measured it are separate concepts.

### Data

**Health Connect record**:
A health or activity record available to the exporter through Health Connect, with its source and timing information. Availability depends on what source apps publish and what the exporter is permitted to read.

**EMA**:
Ecological Momentary Assessment: a short self-report check-in about the user's experience at that moment. In this project, EMA is collected locally by the app and kept distinct from Health Connect records; only answered check-ins are eligible for upload.
_Avoid_: bracelet measurement, Health Connect record

**Daily snapshot**:
An exported representation of a local calendar day's eligible data in Google Drive. Refreshing that day's export replaces its snapshot rather than creating a separate historical version.

**Export eligibility**:
Whether a record meets the app's rules for inclusion in an upload, including its type and source or its EMA answer status. A record can be visible in Details without being eligible for export.

### Interface

**Calendar view**:
The main-screen view of local calendar dates and their upload status, where the user selects days for actions. It represents export history, not proof that the bracelet collected every expected measurement.
_Avoid_: calendar when referring to a scheduling app

**Selected days**:
The dates selected in the calendar view for the Upload or Details action.

**Upload button**:
The action under the calendar, currently labeled "Upload selected", that requests an upload for the selected days after confirmation.

**Details button**:
The action under the calendar labeled "Details" that opens the details screen for the selected days.

**Details screen**:
The view for inspecting a selected day's Health Connect records and local EMA check-ins by data type. It shows accessible sources and export eligibility, including records excluded from uploads.

**Upload status**:
The calendar's indication of confirmed export coverage for a day, including uploaded, partially uploaded, not uploaded, pending/retrying, and unknown. Unknown means coverage cannot currently be established, not that records are absent.

### Processes

**Reading Health Connect data**:
Retrieving permitted health and activity records from Health Connect for inspection or export. This does not itself upload anything to Google Drive or initiate bracelet synchronization.
_Avoid_: grabbing data, uploading from the bracelet

**Uploading to Google Drive**:
Sending eligible export data to the user's Google Drive storage and confirming that it was saved. Reading records successfully does not by itself mean an upload succeeded.
_Avoid_: sync when it is unclear which transfer is meant

**Manual backfill**:
A user-requested export for selected calendar days to fill missing export coverage. It is initiated by the Upload button.

**Automatic export**:
A background export that reads eligible data and uploads it without a calendar selection by the user.

**Bracelet synchronization**:
The transfer of measurements from the fitness bracelet to Mi Fitness. Publication from Mi Fitness to Health Connect and uploading from the exporter to Drive are separate transfers.
