package dev.reva.healthexporter

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CancellationException

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
    val confirmed = mergeIntervals(relevant.filter { it.status == HistoryBatchStatus.CONFIRMED }.map { it.coveredInterval })
    if (confirmed.isEmpty()) return DayCoverage.NOT_UPLOADED
    val covered = confirmed.sumOf { interval ->
        val start = maxOf(interval.startInclusive, day.startInclusive)
        val end = minOf(interval.endExclusive, day.endExclusive)
        if (start.isBefore(end)) Duration.between(start, end).toMillis() else 0L
    }
    return if (covered >= day.duration.toMillis()) DayCoverage.UPLOADED else DayCoverage.PARTIALLY_UPLOADED
}

fun missingIntervals(day: TimeWindow, entries: List<ExportHistoryEntry>): List<TimeWindow> {
    val confirmed = mergeIntervals(entries.filter { it.status == HistoryBatchStatus.CONFIRMED }.map { it.coveredInterval })
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

interface ExportHistoryStore {
    fun entries(destinationKey: String): List<ExportHistoryEntry>
    fun upsert(entry: ExportHistoryEntry)
    fun replaceConfirmed(destinationKey: String, entries: List<ExportHistoryEntry>)
}

sealed interface HistoryRefreshResult {
    data class Success(val entries: List<ExportHistoryEntry>) : HistoryRefreshResult
    data class Unknown(val reason: String) : HistoryRefreshResult
}

class DriveHistoryInventoryRefresher(
    private val gateway: GoogleDriveGateway,
    private val historyStore: ExportHistoryStore,
    private val installationId: String,
    private val destinationKey: String,
) {
    suspend fun refresh(): HistoryRefreshResult {
        return try {
            gateway.verifyAccess()
            val files = gateway.findFiles(appProperties = mapOf("installationId" to installationId))
            val parsed = files.map { parseDriveHistoryEntry(it.appProperties, destinationKey) }
            if (parsed.any { it == null }) {
                HistoryRefreshResult.Unknown("App-created Drive metadata is incomplete.")
            } else {
                val entries = parsed.filterNotNull()
                historyStore.replaceConfirmed(destinationKey, entries)
                HistoryRefreshResult.Success(entries)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: GoogleDriveException.AuthorizationException) {
            HistoryRefreshResult.Unknown("Google Drive authorization is required.")
        } catch (_: Exception) {
            HistoryRefreshResult.Unknown("Google Drive inventory could not be refreshed.")
        }
    }
}

class InMemoryExportHistoryStore : ExportHistoryStore {
    private val values = mutableListOf<ExportHistoryEntry>()
    override fun entries(destinationKey: String) = values.filter { it.destinationKey == destinationKey }
    override fun upsert(entry: ExportHistoryEntry) {
        values.removeAll { it.destinationKey == entry.destinationKey && it.batchId == entry.batchId }
        values += entry
    }
    override fun replaceConfirmed(destinationKey: String, entries: List<ExportHistoryEntry>) {
        val confirmedIds = entries.map { it.batchId }.toSet()
        values.removeAll {
            it.destinationKey == destinationKey &&
                (it.status == HistoryBatchStatus.CONFIRMED || it.batchId in confirmedIds)
        }
        values += entries.filter { it.destinationKey == destinationKey && it.status == HistoryBatchStatus.CONFIRMED }
    }
}

class SharedPreferencesExportHistoryStore(
    private val preferences: SharedPreferences,
) : ExportHistoryStore {
    constructor(context: Context) : this(context.getSharedPreferences("reva_export_history", Context.MODE_PRIVATE))

    override fun entries(destinationKey: String): List<ExportHistoryEntry> = readAll().filter { it.destinationKey == destinationKey }

    override fun upsert(entry: ExportHistoryEntry) {
        val all = readAll().filterNot { it.destinationKey == entry.destinationKey && it.batchId == entry.batchId } + entry
        writeAll(all)
    }

    override fun replaceConfirmed(destinationKey: String, entries: List<ExportHistoryEntry>) {
        val confirmedIds = entries.map { it.batchId }.toSet()
        val pendingAndOther = readAll().filterNot {
            it.destinationKey == destinationKey &&
                (it.status == HistoryBatchStatus.CONFIRMED || it.batchId in confirmedIds)
        }
        writeAll(pendingAndOther + entries.filter { it.destinationKey == destinationKey })
    }

    private fun readAll(): List<ExportHistoryEntry> = try {
        val raw = preferences.getString("entries", null) ?: return emptyList()
        JsonParser.parseString(raw).asJsonArray.mapNotNull { element ->
            val obj = element.asJsonObject
            parseDriveHistoryEntry(
                mapOf(
                    "batchId" to obj.get("batchId")?.asString.orEmpty(),
                    "windowStart" to obj.get("windowStart")?.asString.orEmpty(),
                    "windowEnd" to obj.get("windowEnd")?.asString.orEmpty(),
                    "historyStatus" to obj.get("status")?.asString.orEmpty(),
                    "historyUpdatedAt" to obj.get("updatedAt")?.asString.orEmpty(),
                ),
                obj.get("destinationKey")?.asString.orEmpty(),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun writeAll(entries: List<ExportHistoryEntry>) {
        val json = JsonArray().apply {
            entries.forEach { entry -> add(JsonObject().apply {
                addProperty("batchId", entry.batchId)
                addProperty("windowStart", entry.coveredInterval.startInclusive.toString())
                addProperty("windowEnd", entry.coveredInterval.endExclusive.toString())
                addProperty("status", entry.status.name)
                addProperty("destinationKey", entry.destinationKey)
                addProperty("updatedAt", entry.updatedAt.toString())
            }) }
        }
        preferences.edit().putString("entries", Gson().toJson(json)).commit()
    }
}

fun parseDriveHistoryEntry(properties: Map<String, String>, destinationKey: String): ExportHistoryEntry? {
    return try {
        val id = properties["batchId"]?.takeIf { it.isNotBlank() } ?: return null
        val start = Instant.parse(properties["windowStart"] ?: return null)
        val end = Instant.parse(properties["windowEnd"] ?: return null)
        ExportHistoryEntry(
            id,
            TimeWindow(start, end),
            HistoryBatchStatus.valueOf(properties["historyStatus"] ?: HistoryBatchStatus.CONFIRMED.name),
            destinationKey.takeIf { it.isNotBlank() } ?: return null,
            properties["historyUpdatedAt"]?.let(Instant::parse) ?: Instant.EPOCH,
        )
    } catch (_: Exception) {
        null
    }
}

fun destinationHistoryKey(accountId: String?, destinationName: String): String {
    val source = "${accountId ?: "anonymous"}|$destinationName"
    return MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
        .take(16).joinToString("") { "%02x".format(it) }
}

fun stableBackfillBatchId(destinationKey: String, window: TimeWindow): String {
    val source = "$destinationKey|${window.startInclusive}|${window.endExclusive}"
    return "backfill-" + MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
        .take(16).joinToString("") { "%02x".format(it) }
}

sealed interface ManualBackfillResult {
    data class Success(val confirmed: List<ExportHistoryEntry>) : ManualBackfillResult
    data class NoRecordsFound(val dates: List<LocalDate>) : ManualBackfillResult
    data class Retrying(val batchId: String, val message: String) : ManualBackfillResult
    data class Failure(val message: String) : ManualBackfillResult
}

interface ManualBackfillPendingStore {
    fun get(destinationKey: String, batchId: String): ExportBatch?
    fun save(destinationKey: String, batch: ExportBatch)
    fun remove(destinationKey: String, batchId: String)
}

class InMemoryManualBackfillPendingStore : ManualBackfillPendingStore {
    private val batches = mutableMapOf<Pair<String, String>, ExportBatch>()
    override fun get(destinationKey: String, batchId: String) = batches[destinationKey to batchId]
    override fun save(destinationKey: String, batch: ExportBatch) { batches[destinationKey to batch.header.batchId] = batch }
    override fun remove(destinationKey: String, batchId: String) { batches.remove(destinationKey to batchId) }
}

class SharedPreferencesManualBackfillPendingStore(
    private val preferences: SharedPreferences,
    private val serializer: ExportBatchSerializer = ExportBatchSerializer(),
) : ManualBackfillPendingStore {
    constructor(context: Context) : this(context.getSharedPreferences("reva_manual_backfill_pending", Context.MODE_PRIVATE))
    override fun get(destinationKey: String, batchId: String): ExportBatch? = try {
        preferences.getString(key(destinationKey, batchId), null)?.let(serializer::parseJson)
    } catch (_: Exception) { null }
    override fun save(destinationKey: String, batch: ExportBatch) {
        preferences.edit().putString(key(destinationKey, batch.header.batchId), serializer.serializeToJson(batch)).commit()
    }
    override fun remove(destinationKey: String, batchId: String) {
        preferences.edit().remove(key(destinationKey, batchId)).commit()
    }
    private fun key(destinationKey: String, batchId: String) = "$destinationKey:$batchId"
}

class ManualBackfillCoordinator(
    private val exportStateStore: ExportStateStore,
    private val historyStore: ExportHistoryStore,
    private val recordReader: HealthExportRecordReader,
    private val destination: ExportDestination,
    private val destinationKey: String,
    private val clock: DiagnosticClock = SystemDiagnosticClock,
    private val pendingStore: ManualBackfillPendingStore = InMemoryManualBackfillPendingStore(),
) {
    suspend fun uploadDays(dates: List<LocalDate>, zoneId: ZoneId): ManualBackfillResult {
        require(dates.isNotEmpty())
        val confirmed = mutableListOf<ExportHistoryEntry>()
        val emptyDates = mutableListOf<LocalDate>()
        for (date in dates.distinct().sorted()) {
            val day = localDayWindow(date, zoneId)
            val missing = missingIntervals(day, historyStore.entries(destinationKey))
            for (window in missing) {
                val id = stableBackfillBatchId(destinationKey, window)
                val existing = historyStore.entries(destinationKey).firstOrNull { it.batchId == id }
                val now = clock.now(zoneId).toInstant()
                val persistedBatch = pendingStore.get(destinationKey, id)
                val records = if (persistedBatch == null) {
                    try {
                        recordReader.readRecords(window).distinctBy { it.recordType to (it.metadata.clientRecordId ?: it.metadata.recordId) }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (e: Exception) {
                        return ManualBackfillResult.Retrying(id, "Health Connect read failed: ${e.message ?: "read error"}")
                    }
                } else persistedBatch.records
                if (records.isEmpty()) {
                    if (existing == null) emptyDates += date
                    continue
                }
                val pending = ExportHistoryEntry(id, window, HistoryBatchStatus.PENDING, destinationKey, now)
                historyStore.upsert(pending)
                val batch = persistedBatch ?: ExportBatch(
                    BatchHeader(
                        installationId = exportStateStore.getInstallationId(),
                        batchId = id,
                        createdAt = now,
                        timeWindow = window,
                        recordCount = records.size,
                        recordTypes = records.map { it.recordType }.distinct().sorted(),
                    ),
                    records,
                )
                if (persistedBatch == null) pendingStore.save(destinationKey, batch)
                when (val upload = destination.upload(batch)) {
                    is UploadResult.Success -> {
                        val done = pending.copy(status = HistoryBatchStatus.CONFIRMED, updatedAt = clock.now(zoneId).toInstant())
                        historyStore.upsert(done)
                        pendingStore.remove(destinationKey, id)
                        confirmed += done
                    }
                    is UploadResult.Failure -> return if (upload.isRetryable) {
                        ManualBackfillResult.Retrying(id, upload.message)
                    } else {
                        ManualBackfillResult.Failure(upload.message)
                    }
                }
            }
        }
        return if (confirmed.isNotEmpty()) ManualBackfillResult.Success(confirmed)
        else ManualBackfillResult.NoRecordsFound(emptyDates.distinct())
    }
}

data class ExportHistoryRow(val date: LocalDate, val coverage: DayCoverage, val selected: Boolean)
data class ExportHistoryScreenState(
    val zoneId: ZoneId,
    val rows: List<ExportHistoryRow> = emptyList(),
    val canUpload: Boolean = false,
    val uploadStarted: Boolean = false,
)
data class BackfillConfirmation(val dates: List<LocalDate>, val rangeLabel: String)

class ExportHistoryPresenter(private val zoneId: ZoneId = ZoneId.systemDefault()) {
    var state = ExportHistoryScreenState(zoneId)
        private set
    fun show(dates: List<LocalDate>, entries: List<ExportHistoryEntry>, inventoryKnown: Boolean) {
        state = state.copy(rows = dates.map { date ->
            ExportHistoryRow(date, classifyDayCoverage(localDayWindow(date, zoneId), entries, inventoryKnown), false)
        }, canUpload = false, uploadStarted = false)
    }
    fun toggle(date: LocalDate) {
        val rows = state.rows.map { row ->
            if (row.date == date && row.coverage != DayCoverage.UPLOADED && row.coverage != DayCoverage.UNKNOWN) {
                row.copy(selected = !row.selected)
            } else row
        }
        state = state.copy(rows = rows, canUpload = rows.any { it.selected })
    }
    fun requestConfirmation(): BackfillConfirmation {
        val dates = state.rows.filter { it.selected }.map { it.date }.sorted()
        require(dates.isNotEmpty())
        val label = if (dates.size == 1) dates.single().toString() else "${dates.first()} – ${dates.last()}"
        return BackfillConfirmation(dates, label)
    }
    fun confirmUpload(): List<LocalDate> {
        val dates = requestConfirmation().dates
        state = state.copy(uploadStarted = true, canUpload = false)
        return dates
    }
}
