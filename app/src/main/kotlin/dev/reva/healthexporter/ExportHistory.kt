package dev.reva.healthexporter

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class DayCoverage { UPLOADED, PARTIALLY_UPLOADED, NOT_UPLOADED, PENDING_RETRYING, UNKNOWN }

enum class HistoryBatchStatus { PENDING, CONFIRMED }

data class ExportHistoryEntry(
    val batchId: String,
    val coveredInterval: TimeWindow,
    val status: HistoryBatchStatus,
    val destinationKey: String,
    val updatedAt: Instant,
) {
    init {
        require(batchId.isNotBlank())
        require(destinationKey.isNotBlank())
    }
}

val TimeWindow.duration: Duration get() = Duration.between(startInclusive, endExclusive)

fun localDayWindow(date: LocalDate, zoneId: ZoneId): TimeWindow = TimeWindow(
    date.atStartOfDay(zoneId).toInstant(),
    date.plusDays(1).atStartOfDay(zoneId).toInstant(),
)

fun classifyDayCoverage(
    day: TimeWindow,
    entries: List<ExportHistoryEntry>,
    inventoryKnown: Boolean = true,
): DayCoverage {
    if (!inventoryKnown) return DayCoverage.UNKNOWN
    val relevant = entries.filter { it.coveredInterval.overlaps(day) }
    if (relevant.any { it.status == HistoryBatchStatus.PENDING }) return DayCoverage.PENDING_RETRYING
    val confirmed = mergeIntervals(
        relevant.filter { it.status == HistoryBatchStatus.CONFIRMED }.map { it.coveredInterval },
    )
    if (confirmed.isEmpty()) return DayCoverage.NOT_UPLOADED
    val covered = confirmed.sumOf { interval ->
        val start = maxOf(interval.startInclusive, day.startInclusive)
        val end = minOf(interval.endExclusive, day.endExclusive)
        if (start.isBefore(end)) Duration.between(start, end).toMillis() else 0L
    }
    return if (covered >= day.duration.toMillis()) DayCoverage.UPLOADED else DayCoverage.PARTIALLY_UPLOADED
}

fun missingIntervals(day: TimeWindow, entries: List<ExportHistoryEntry>): List<TimeWindow> {
    val confirmed = mergeIntervals(
        entries.filter { it.status == HistoryBatchStatus.CONFIRMED }.map { it.coveredInterval },
    )
    val result = mutableListOf<TimeWindow>()
    var cursor = day.startInclusive
    confirmed.forEach { interval ->
        val start = maxOf(interval.startInclusive, day.startInclusive)
        val end = minOf(interval.endExclusive, day.endExclusive)
        if (cursor.isBefore(start)) result += TimeWindow(cursor, start)
        if (cursor.isBefore(end)) cursor = end
    }
    if (cursor.isBefore(day.endExclusive)) result += TimeWindow(cursor, day.endExclusive)
    return result
}

private fun TimeWindow.overlaps(other: TimeWindow): Boolean =
    startInclusive.isBefore(other.endExclusive) && other.startInclusive.isBefore(endExclusive)

private fun mergeIntervals(intervals: List<TimeWindow>): List<TimeWindow> {
    val sorted = intervals.sortedBy { it.startInclusive }
    if (sorted.isEmpty()) return emptyList()
    val merged = mutableListOf<TimeWindow>()
    var current = sorted.first()
    sorted.drop(1).forEach { next ->
        if (!next.startInclusive.isAfter(current.endExclusive)) {
            current = TimeWindow(current.startInclusive, maxOf(current.endExclusive, next.endExclusive))
        } else {
            merged += current
            current = next
        }
    }
    merged += current
    return merged
}
