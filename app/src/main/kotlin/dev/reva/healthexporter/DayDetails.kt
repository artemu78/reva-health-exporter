package dev.reva.healthexporter

import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.CancellationException

/** All types the app can inspect, including types/sources deliberately excluded from exports. */
enum class DetailType(val label: String) {
    EMA("EMA"), SLEEP("Sleep"), STEPS("Steps"), WORKOUTS("Workouts"),
    HEART_RATE("Heart rate"), RESTING_HEART_RATE("Resting heart rate"),
    DISTANCE("Distance"), CALORIES("Calories"), OXYGEN("Oxygen saturation"),
}

data class DetailRecord(val text: String, val exportEligible: Boolean)
data class DetailGroup(
    val type: DetailType,
    val summary: String,
    val records: List<DetailRecord> = emptyList(),
    val problem: String? = null,
)
data class DayDetails(val date: LocalDate, val groups: List<DetailGroup>)

fun interface DayDetailsReader {
    suspend fun read(type: DetailType, window: TimeWindow): List<CanonicalRecord>
}

class DayDetailsLoader(private val reader: DayDetailsReader, private val emaStore: EmaEventStore) {
    suspend fun load(date: LocalDate, zone: ZoneId): DayDetails {
        val window = localDayWindow(date, zone)
        return DayDetails(date, DetailType.entries.map { type ->
            try {
                if (type == DetailType.EMA) {
                    val events = emaStore.all().filter { it.scheduleDate == date }.sortedBy { it.scheduledAt }
                    DetailGroup(type, "${events.size} records · ${events.count { it.status == EmaResponseStatus.ANSWERED }} answered",
                        events.map { DetailRecord(formatEma(it, zone), it.status == EmaResponseStatus.ANSWERED) })
                } else {
                    val records = reader.read(type, window).filter {
                        it.startTime >= window.startInclusive && it.startTime < window.endExclusive
                    }.sortedWith(compareBy({ it.startTime }, { it.metadata.origin }, { it.metadata.recordId }))
                    DetailGroup(type, summarize(records), records.map {
                        DetailRecord(formatHealth(it, zone), isExportSource(it))
                    })
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                DetailGroup(type, "Unavailable", problem = "Read permission missing or revoked. Grant access in Settings, then retry.")
            } catch (_: UnsupportedOperationException) {
                DetailGroup(type, "Unavailable", problem = "This data type or Health Connect is unavailable on this device.")
            } catch (_: Exception) {
                // Never put exception messages (which may contain health values) into UI/logs.
                DetailGroup(type, "Read failed", problem = "Could not read this group. Retry; other groups remain available.")
            }
        })
    }
}

private fun number(value: Double) = String.format(Locale.US, "%.1f", value)
private fun duration(seconds: Long) = "${seconds / 3600}h ${(seconds % 3600) / 60}m ${seconds % 60}s"
private fun summarize(records: List<CanonicalRecord>): String {
    val prefix = "${records.size} records"
    if (records.isEmpty()) return prefix
    val value = when (records.first()) {
        is CanonicalStepsRecord -> "${records.sumOf { (it as CanonicalStepsRecord).count }} steps (raw sum)"
        is CanonicalSleepSessionRecord -> {
            val sleep = records.filterIsInstance<CanonicalSleepSessionRecord>()
            val sessions = duration(sleep.sumOf { Duration.between(it.startTime, it.endTime).seconds })
            val asleep = duration(sleep.flatMap { it.stages }.filter { it.stage in setOf(2, 4, 5, 6) }
                .sumOf { Duration.between(it.startTime, it.endTime).seconds })
            val stages = if (sleep.all { it.stages.isEmpty() }) "Asleep time unavailable" else "$asleep asleep in recorded stages"
            "$stages · $sessions session duration (raw sums)"
        }
        is CanonicalExerciseSessionRecord -> "${duration(records.sumOf { Duration.between(it.startTime, it.endTime).seconds })} workouts (raw duration)"
        is CanonicalDistanceRecord -> "${number(records.sumOf { (it as CanonicalDistanceRecord).distanceMeters })} m (raw sum)"
        is CanonicalTotalCaloriesBurnedRecord -> "${number(records.sumOf { (it as CanonicalTotalCaloriesBurnedRecord).energyKilocalories })} kcal (raw sum)"
        is CanonicalHeartRateRecord -> "${records.sumOf { (it as CanonicalHeartRateRecord).samples.size }} samples"
        is CanonicalRestingHeartRateRecord -> "${number(records.map { (it as CanonicalRestingHeartRateRecord).beatsPerMinute }.average())} bpm average"
        is CanonicalOxygenSaturationRecord -> "${number(records.map { (it as CanonicalOxygenSaturationRecord).percentage }.average())}% average"
    }
    return "$prefix · $value"
}

private fun isExportSource(record: CanonicalRecord): Boolean {
    val type = when (record) {
        is CanonicalStepsRecord -> androidx.health.connect.client.records.StepsRecord::class
        is CanonicalSleepSessionRecord -> androidx.health.connect.client.records.SleepSessionRecord::class
        is CanonicalExerciseSessionRecord -> androidx.health.connect.client.records.ExerciseSessionRecord::class
        is CanonicalDistanceRecord -> androidx.health.connect.client.records.DistanceRecord::class
        is CanonicalTotalCaloriesBurnedRecord -> androidx.health.connect.client.records.TotalCaloriesBurnedRecord::class
        is CanonicalHeartRateRecord -> androidx.health.connect.client.records.HeartRateRecord::class
        is CanonicalOxygenSaturationRecord -> androidx.health.connect.client.records.OxygenSaturationRecord::class
        is CanonicalRestingHeartRateRecord -> return false
    }
    return record.metadata.origin == ExportSourcePolicy.allowedPackageName(type)
}

private fun formatHealth(record: CanonicalRecord, zone: ZoneId): String = buildString {
    appendLine("${record.startTime.atZone(zone)} → ${record.endTime.atZone(zone)}")
    when (record) {
        is CanonicalStepsRecord -> appendLine("${record.count} steps")
        is CanonicalDistanceRecord -> appendLine("${record.distanceMeters} m")
        is CanonicalTotalCaloriesBurnedRecord -> appendLine("${record.energyKilocalories} kcal")
        is CanonicalRestingHeartRateRecord -> appendLine("${record.beatsPerMinute} bpm")
        is CanonicalOxygenSaturationRecord -> appendLine("${record.percentage}%")
        is CanonicalHeartRateRecord -> record.samples.forEach { appendLine("${it.time.atZone(zone)}: ${it.beatsPerMinute} bpm") }
        is CanonicalSleepSessionRecord -> {
            appendLine("Session duration: ${duration(Duration.between(record.startTime, record.endTime).seconds)}")
            record.title?.let { appendLine("Title: $it") }
            record.notes?.let { appendLine("Notes: $it") }
            record.stages.forEach {
                val name = when (it.stage) {
                    0 -> "Unknown"; 1 -> "Awake"; 2 -> "Sleeping"; 3 -> "Out of bed"
                    4 -> "Light"; 5 -> "Deep"; 6 -> "REM"; 7 -> "Awake in bed"; else -> "Unknown (${it.stage})"
                }
                appendLine("${it.startTime.atZone(zone)} → ${it.endTime.atZone(zone)}: $name")
            }
        }
        is CanonicalExerciseSessionRecord -> {
            appendLine("Exercise type: ${exerciseTypeLabel(record.exerciseType)} · ${duration(Duration.between(record.startTime, record.endTime).seconds)}")
            record.title?.let { appendLine("Title: $it") }
            record.notes?.let { appendLine("Notes: $it") }
            record.segments.forEach { appendLine("Segment ${it.segmentType}: ${it.startTime.atZone(zone)} → ${it.endTime.atZone(zone)}, ${it.repetitions} repetitions") }
            record.laps.forEach { appendLine("Lap: ${it.startTime.atZone(zone)} → ${it.endTime.atZone(zone)}, ${it.lengthMeters ?: "unknown"} m") }
        }
    }
    appendLine("Source: ${record.metadata.origin}")
    appendLine(if (isExportSource(record)) "Included by export source policy" else "Excluded by export source policy")
    appendLine("Record ID: ${record.metadata.recordId ?: "unavailable"}")
    record.metadata.clientRecordId?.let { appendLine("Client ID: $it · version ${record.metadata.clientRecordVersion}") }
    record.metadata.device?.let { appendLine("Device: ${it.manufacturer.orEmpty()} ${it.model.orEmpty()} · type ${it.type}") }
    record.metadata.recordingMethod?.let { appendLine("Recording method: $it") }
    record.metadata.lastModifiedTime?.let { appendLine("Modified: ${it.atZone(zone)}") }
    append("Original offsets: ${record.startZoneOffset ?: "unknown"} → ${record.endZoneOffset ?: "unknown"}")
}

private fun formatEma(event: EmaEvent, zone: ZoneId): String = buildString {
    appendLine("Scheduled: ${event.scheduledAt.atZone(zone)}")
    appendLine("Status: ${event.status.wireValue}")
    event.answeredAt?.let { appendLine("Answered: ${it.atZone(zone)}") }
    event.answers?.let {
        appendLine("Mood ${it.mood}/5 · Energy ${it.energy}/5 · Focus ${it.focus}/5 · Stress ${it.stress}/5")
        it.additional.toSortedMap().forEach { (key, value) -> appendLine("$key: $value") }
    }
    event.activity?.let { appendLine("Activity: ${event.activityLabel ?: it} ($it)") }
    event.note?.let { appendLine("Note: $it") }
    appendLine(if (event.status == EmaResponseStatus.ANSWERED) "Eligible for export" else "Not exported: only answered EMA events are uploaded")
    appendLine("Source: local EMA · ID: ${event.id}")
    append("Schedule date: ${event.scheduleDate} · Original timezone: ${event.timezone} · Schema ${event.schemaVersion}")
}
