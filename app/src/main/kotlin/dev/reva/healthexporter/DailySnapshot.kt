package dev.reva.healthexporter

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Stable identity and correct local-day windows for mutable Drive snapshots. */
data class DailySnapshotKey(
    val destination: String,
    val account: String?,
    val timezone: ZoneId,
    val date: LocalDate,
) {
    val identity: String
        get() {
            val source = listOf(destination, account.orEmpty(), timezone.id, date.toString()).joinToString("\u0000")
            return "daily-" + MessageDigest.getInstance("SHA-256")
                .digest(source.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

    val filename: String get() = "$date.json"
    val window: TimeWindow get() = localDayWindow(date, timezone)
}

fun dailySnapshotKey(destination: String, account: String?, timezone: ZoneId, date: LocalDate) =
    DailySnapshotKey(destination, account, timezone, date)

fun dailySnapshotHeader(
    installationId: String,
    key: DailySnapshotKey,
    createdAt: Instant,
    records: List<CanonicalRecord>,
): BatchHeader = BatchHeader(
    installationId = installationId,
    batchId = key.identity,
    createdAt = createdAt,
    timeWindow = key.window,
    recordCount = records.size,
    recordTypes = records.map { it.recordType }.distinct().sorted(),
    exportDate = key.date.toString(),
    exportTimezone = key.timezone.id,
    dailyIdentity = key.identity,
)

fun dailySnapshotDate(now: Instant, timezone: ZoneId): LocalDate = now.atZone(timezone).toLocalDate()

fun dailySnapshotRefreshDates(now: Instant, timezone: ZoneId): List<LocalDate> = listOf(
    dailySnapshotDate(now, timezone),
    dailySnapshotDate(now, timezone).minusDays(1),
)
