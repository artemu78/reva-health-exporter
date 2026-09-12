# Main upload matrix

Design source: Stitch project `5769176742869058112`, screen `4bd90a4b3ea848ec9ccb0ed75ffb0bb6` (Reva Health Exporter - Main Upload Matrix).

The Records page uses the reference palette, connection cards, export status, Monday-first five-week calendar, window navigation, and contextual selection bar. Dates and coverage come from the existing export history. Future dates cannot be selected. Selection and the current window survive activity recreation.

The old list, load-more button, timezone heading, diagnostics and background-probe controls are removed from Records. Existing connection management and manual full export are available under Vaults; diagnostic tools and the installed version are under Settings. Audit Log displays the latest recorded export summary; the app does not yet store a complete execution audit trail.

The mock email, record counts and encryption claim are not copied. Unknown inventory is labeled Unknown because an absent export does not establish that Health Connect has no records. Existing upload scheduling and export format are unchanged.

Verification: `./gradlew test lintDebug assembleDebug connectedDebugAndroidTest` on the API 30 emulator. Tests cover calendar boundaries, navigation, disabled future dates, selection/cancellation, disconnected upload gating, refresh, and recreation. No live account or physical-phone test is claimed for this presentation change.
