# Mi Fitness and Smart Band 9 Health Connect Compatibility Report

This document records the physical-device verification results for health metrics written by Xiaomi Mi Fitness from a Xiaomi Smart Band 9 to Health Connect on Android 11.

In accordance with the project testing strategy and privacy rules ([TESTING.md](../TESTING.md), [AGENTS.md](../AGENTS.md)), all observations are sanitized: no personal health values, exact counts, credentials, or tokens are recorded.

---

## 1. Executive summary & gate decision

- **Milestone gate status:** **PASSED (PROCEED WITH NARROWED SCOPE)**
- **Target wearable:** Xiaomi Smart Band 9
- **Companion app:** Xiaomi Mi Fitness (`com.mi.health`)
- **Platform layer:** Health Connect on Android 11 (`com.google.android.apps.healthdata`)
- **Diagnostic application:** Reva Health Exporter (Release / Debug v0.1.0)
- **Primary outcome:** Mi Fitness reliably publishes five core record types (`StepsRecord`, `HeartRateRecord`, `DistanceRecord`, `TotalCaloriesBurnedRecord`, and `SleepSessionRecord`) to Health Connect. `ExerciseSessionRecord` is published conditionally during explicit workout tracking. Standalone `RestingHeartRateRecord` and `OxygenSaturationRecord` are not emitted to Health Connect by the tested Mi Fitness version.
- **Decision:** Proceed to **Issue 7 (Define version-1 health-export schema)** and **Issue 8 (Verify background Health Connect access)** focusing on confirmed core metrics.

---

## 2. Test environment & device configuration

| Component | Specification / Version |
|---|---|
| Device | Android 11 physical device (API 30) |
| Wearable | Xiaomi Smart Band 9 (Firmware up to date) |
| Companion App | Xiaomi Mi Fitness (Android package: `com.mi.health`) |
| Provider | Health Connect (`com.google.android.apps.healthdata`) |
| Diagnostic App | Reva Health Exporter `0.1.0` (Build `330c949` / `cc3345d`) |
| Permissions Granted | All 8 read permissions (`READ_STEPS`, `READ_HEART_RATE`, `READ_RESTING_HEART_RATE`, `READ_SLEEP`, `READ_DISTANCE`, `READ_TOTAL_CALORIES_BURNED`, `READ_EXERCISE`, `READ_OXYGEN_SATURATION`) |

---

## 3. Physical-device verification protocol

The following multi-step protocol was executed twice on the target physical phone:

### Scenario 1: Initial 24-hour diagnostic probe
1. Ensure the band is paired and actively collecting data.
2. Open Reva Health Exporter with all read permissions granted.
3. Select the **Last 24 hours** window.
4. Execute diagnostic probe and record metric statuses, data origins, and time coverage.
5. Cross-check against the Health Connect system viewer / Health Connect Toolbox.

### Scenario 2: Seven-day diagnostic probe
1. Select the **Last 7 days** window.
2. Refresh diagnostics.
3. Verify historical data continuity, record count scaling, and origin attribution.

### Scenario 3: Mi Fitness resynchronization
1. Perform physical activity with the band (walking / movement).
2. Open Mi Fitness and pull down to trigger synchronization from Smart Band 9.
3. Switch back to Reva Health Exporter and tap **Refresh**.
4. Confirm that newly collected records appear in both 24-hour and 7-day diagnostic windows.

### Scenario 4: Device reboot
1. Restart the Android 11 phone.
2. After boot, launch Reva Health Exporter without opening Mi Fitness first.
3. Verify that Health Connect remains available, permissions remain intact, and diagnostic queries execute successfully.

### Scenario 5: Sanitized local snapshot export
1. Tap **Export diagnostic snapshot**.
2. Select destination in Android Storage Access Framework (SAF) document picker (`reva-health-diagnostic.json`).
3. Verify that the exported JSON contains valid schema structure, platform metadata, type summaries, and zero raw health measurements.

---

## 4. Sanitized evidence & candidate type classification

| Candidate Metric | Health Connect Record Type | 24-Hour Window | 7-Day Window | Data Origin | Classification | Export Scope in Schema v1 |
|---|---|---|---|---|---|---|
| **Steps** | `StepsRecord` | Populated | Populated | `com.mi.health` | **CONFIRMED** | Core (Mandatory) |
| **Heart Rate** | `HeartRateRecord` | Populated | Populated | `com.mi.health` | **CONFIRMED** | Core (Mandatory) |
| **Distance** | `DistanceRecord` | Populated | Populated | `com.mi.health` | **CONFIRMED** | Core (Mandatory) |
| **Total Calories** | `TotalCaloriesBurnedRecord` | Populated | Populated | `com.mi.health` | **CONFIRMED** | Core (Mandatory) |
| **Sleep** | `SleepSessionRecord` | Populated | Populated | `com.mi.health` | **CONFIRMED** | Core (Mandatory) |
| **Exercise Sessions** | `ExerciseSessionRecord` | Empty (No active workout) | Populated / Empty | `com.mi.health` | **CONFIRMED (Conditional)** | Optional |
| **Resting Heart Rate** | `RestingHeartRateRecord` | Empty | Empty | None | **EMPTY** | Deferred / Optional |
| **Oxygen Saturation** | `OxygenSaturationRecord` | Empty | Empty | None | **EMPTY** | Deferred / Optional |

---

## 5. Detailed findings per record type

### 5.1. Steps (`StepsRecord`) — CONFIRMED
- **Publication Behavior:** Mi Fitness writes step intervals in regular continuous buckets (typically 1-minute to 10-minute intervals).
- **Time Coverage:** Full coverage throughout active hours.
- **Origin:** `com.mi.health`.
- **Recommendation:** Include as core mandatory metric in Schema v1.

### 5.2. Heart Rate (`HeartRateRecord`) — CONFIRMED
- **Publication Behavior:** Emitted as `HeartRateRecord` series containing individual `HeartRateRecord.Sample` points.
- **Synchronization Characteristics:** Mi Fitness syncs heart rate in batches. If the band has not synced recently, or if monitoring is intermittent, a rolling 24-hour window (`[now - 24h, now)`) may show `Empty` if no sync occurred within the exact 24h slice, even if Health Connect contains older daily data. Once Mi Fitness performs a synchronization cycle, the 24-hour and 7-day windows populate consistently.
- **Origin:** `com.mi.health`.
- **Recommendation:** Include as core mandatory metric in Schema v1; parser must handle series sample lists.

### 5.3. Distance (`DistanceRecord`) — CONFIRMED
- **Publication Behavior:** Accompanies step records with length intervals (meters).
- **Origin:** `com.mi.health`.
- **Recommendation:** Include as core mandatory metric in Schema v1.

### 5.4. Total Calories Burned (`TotalCaloriesBurnedRecord`) — CONFIRMED
- **Publication Behavior:** Emitted regularly, combining basal and active energy burn intervals.
- **Origin:** `com.mi.health`.
- **Recommendation:** Include as core mandatory metric in Schema v1.

### 5.5. Sleep (`SleepSessionRecord`) — CONFIRMED
- **Publication Behavior:** Discrete session intervals recorded once per sleep cycle (typically written after morning sync).
- **Origin:** `com.mi.health`.
- **Recommendation:** Include as core mandatory metric in Schema v1.

### 5.6. Exercise Sessions (`ExerciseSessionRecord`) — CONFIRMED (Conditional)
- **Publication Behavior:** Emitted only when the user explicitly starts and stops a workout activity on the Smart Band 9. During normal daily routine without tracked workouts, count is 0 (`Empty`).
- **Origin:** `com.mi.health`.
- **Recommendation:** Model as an optional record type in Schema v1.

### 5.7. Resting Heart Rate (`RestingHeartRateRecord`) — EMPTY
- **Publication Behavior:** No records emitted by Mi Fitness to Health Connect on Android 11. Mi Fitness calculates resting heart rate internally within its own UI but does not write `RestingHeartRateRecord` objects to Health Connect.
- **Origin:** None.
- **Recommendation:** Do not block export pipeline. Mark as optional/omitted in Schema v1.

### 5.8. Oxygen Saturation (`OxygenSaturationRecord`) — EMPTY
- **Publication Behavior:** SpO2 spot checks and continuous tracking from Smart Band 9 are retained within Mi Fitness but not synchronized to Health Connect by current Mi Fitness builds.
- **Origin:** None.
- **Recommendation:** Do not block export pipeline. Mark as optional/omitted in Schema v1.

---

## 6. Investigation of observed anomalies & sync dynamics

### Observation on Heart Rate in rolling 24-hour windows
- **Observation:** In initial testing, Health Connect's calendar day view showed heart rate entries, but a 24-hour diagnostic probe showed `Empty`.
- **Root Cause Analysis:** 
  1. The diagnostic probe uses a rolling time filter: `TimeRangeFilter.between(now.minusDays(1), now)`.
  2. If the user synced the band in the morning of Day 1 and ran the diagnostic in the afternoon of Day 2 without a sync in between, the records in Health Connect belonged to Day 1 outside the `[now - 24h, now)` window.
  3. Selecting the **7-day window** confirmed the historical heart-rate records.
  4. Performing a manual pull-down sync in Mi Fitness immediately populated the 24-hour window with fresh `HeartRateRecord` entries.
- **Conclusion:** Health Connect queries function correctly. Incremental export design (Issue 9) should use watermark checkpoints rather than fixed rolling windows to ensure no records between sync intervals are missed.

---

## 7. Scope definition for Milestone: Export Core

Based on confirmed interoperability data:

1. **Schema v1 Design (Issue 7):**
   - **Supported Core Types:** `StepsRecord`, `HeartRateRecord`, `DistanceRecord`, `TotalCaloriesBurnedRecord`, `SleepSessionRecord`.
   - **Supported Optional Types:** `ExerciseSessionRecord`.
   - **Deferred Types:** `RestingHeartRateRecord`, `OxygenSaturationRecord` (not published by Mi Fitness).
2. **Background Read Testing (Issue 8):**
   - Verify whether Health Connect allows background read worker access on Android 11 for the confirmed core types.
3. **Batching & Checkpoints (Issue 9):**
   - Use deterministic timestamp checkpoints matching the confirmed interval and sample structures.

---

## 8. Verification protocol conclusion

- **Physical Device Protocol:** Completed twice (pre-restart and post-restart).
- **Sanitized Snapshot:** Export verified and passes structure checks.
- **Status:** Issue 6 criteria satisfied. Proceed to Issue 7.
