package dev.reva.healthexporter

import android.content.Context
import android.content.SharedPreferences
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CancellationException

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

    override fun save(destinationKey: String, batch: ExportBatch) {
        batches[destinationKey to batch.header.batchId] = batch
    }

    override fun remove(destinationKey: String, batchId: String) {
        batches.remove(destinationKey to batchId)
    }
}

class SharedPreferencesManualBackfillPendingStore(
    private val preferences: SharedPreferences,
    private val serializer: ExportBatchSerializer = ExportBatchSerializer(),
) : ManualBackfillPendingStore {
    constructor(context: Context) : this(
        context.getSharedPreferences("reva_manual_backfill_pending", Context.MODE_PRIVATE),
    )

    override fun get(destinationKey: String, batchId: String): ExportBatch? = try {
        preferences.getString(key(destinationKey, batchId), null)?.let(serializer::parseJson)
    } catch (_: Exception) {
        null
    }

    override fun save(destinationKey: String, batch: ExportBatch) {
        check(
            preferences.edit()
                .putString(key(destinationKey, batch.header.batchId), serializer.serializeToJson(batch))
                .commit(),
        ) { "Failed to save pending manual backfill batch" }
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
    private val emaEventStore: EmaEventStore? = null,
) {
    private sealed interface WindowUploadResult {
        data class Confirmed(val entry: ExportHistoryEntry) : WindowUploadResult
        data object Empty : WindowUploadResult
        data class Stopped(val result: ManualBackfillResult) : WindowUploadResult
    }

    private sealed interface BatchPreparation {
        data class Ready(val batch: ExportBatch) : BatchPreparation
        data object Empty : BatchPreparation
        data class Stopped(val result: ManualBackfillResult) : BatchPreparation
    }

    private sealed interface RecordReadResult {
        data class Success(val records: List<CanonicalRecord>) : RecordReadResult
        data class Failure(val message: String) : RecordReadResult
    }

    suspend fun uploadDays(
        dates: List<LocalDate>,
        zoneId: ZoneId,
        onDateCompleted: (suspend (LocalDate, Boolean) -> Unit)? = null,
    ): ManualBackfillResult {
        require(dates.isNotEmpty())
        val confirmed = mutableListOf<ExportHistoryEntry>()
        val emptyDates = mutableListOf<LocalDate>()
        for (date in dates.distinct().sorted()) {
            val day = localDayWindow(date, zoneId)
            when (val result = uploadWindow(day, zoneId)) {
                is WindowUploadResult.Confirmed -> {
                    confirmed += result.entry
                    onDateCompleted?.invoke(date, true)
                }
                WindowUploadResult.Empty -> {
                    emptyDates += date
                    onDateCompleted?.invoke(date, false)
                }
                is WindowUploadResult.Stopped -> {
                    onDateCompleted?.invoke(date, false)
                    return result.result
                }
            }
        }
        return summarizeBackfill(confirmed, emptyDates)
    }

    private suspend fun uploadWindow(window: TimeWindow, zoneId: ZoneId): WindowUploadResult {
        val batchId = stableBackfillBatchId(destinationKey, window)
        val now = clock.now(zoneId).toInstant()
        val preparation = try {
            prepareBatch(batchId, window, now, zoneId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            return WindowUploadResult.Stopped(
                ManualBackfillResult.Retrying(
                    batchId,
                    "Failed to prepare manual backfill batch: ${e.message ?: "storage error"}",
                ),
            )
        }
        val batch = when (preparation) {
            is BatchPreparation.Ready -> preparation.batch
            BatchPreparation.Empty -> return WindowUploadResult.Empty
            is BatchPreparation.Stopped -> return WindowUploadResult.Stopped(preparation.result)
        }
        val pending = ExportHistoryEntry(batchId, window, HistoryBatchStatus.PENDING, destinationKey, now)
        historyStore.upsert(pending)
        return classifyUpload(destination.upload(batch), pending, zoneId)
    }

    private suspend fun prepareBatch(
        batchId: String,
        window: TimeWindow,
        now: Instant,
        zoneId: ZoneId,
    ): BatchPreparation {
        pendingStore.get(destinationKey, batchId)?.let { saved ->
            val currentEvents = emaEventStore?.all()?.associateBy(EmaEvent::id)
            val answered = saved.emaEvents.mapNotNull { event ->
                val current = if (currentEvents == null) event else currentEvents[event.id]
                current?.takeIf { it.status == EmaResponseStatus.ANSWERED }
            }
            val batch = if (answered == saved.emaEvents) saved else saved.copy(emaEvents = answered).also {
                pendingStore.save(destinationKey, it)
            }
            return BatchPreparation.Ready(batch)
        }
        val records = when (val read = readRecords(window)) {
            is RecordReadResult.Success -> read.records
            is RecordReadResult.Failure -> return BatchPreparation.Stopped(
                ManualBackfillResult.Retrying(batchId, read.message),
            )
        }
        val emaEvents = if (emaEventStore != null) {
            val windowStartDate = window.startInclusive.atZone(zoneId).toLocalDate()
            val windowEndDate = window.endExclusive.atZone(zoneId).let { dateTime ->
                if (dateTime.toLocalTime() == java.time.LocalTime.MIDNIGHT) {
                    dateTime.toLocalDate()
                } else {
                    dateTime.toLocalDate().plusDays(1)
                }
            }
            emaEventStore.all().filter { event ->
                event.status == EmaResponseStatus.ANSWERED &&
                    !event.scheduleDate.isBefore(windowStartDate) && event.scheduleDate.isBefore(windowEndDate)
            }
        } else {
            emptyList()
        }
        if (records.isEmpty() && emaEvents.isEmpty()) return BatchPreparation.Empty
        val batch = buildBatch(batchId, window, now, records, emaEvents)
        pendingStore.save(destinationKey, batch)
        return BatchPreparation.Ready(batch)
    }

    private suspend fun readRecords(window: TimeWindow): RecordReadResult {
        return try {
            RecordReadResult.Success(
                ExportRecordCanonicalizer.canonicalize(recordReader.readRecords(window)),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            RecordReadResult.Failure("Health Connect read failed: ${e.message ?: "read error"}")
        }
    }

    private fun buildBatch(
        batchId: String,
        window: TimeWindow,
        now: Instant,
        records: List<CanonicalRecord>,
        emaEvents: List<EmaEvent> = emptyList(),
    ): ExportBatch = ExportBatch(
        BatchHeader(
            installationId = exportStateStore.getInstallationId(),
            batchId = batchId,
            createdAt = now,
            timeWindow = window,
            recordCount = records.size,
            recordTypes = records.map { it.recordType }.distinct().sorted(),
        ),
        records,
        emaEvents,
    )

    private fun classifyUpload(
        upload: UploadResult,
        pending: ExportHistoryEntry,
        zoneId: ZoneId,
    ): WindowUploadResult = when (upload) {
        is UploadResult.Success -> confirmUpload(pending, zoneId)
        is UploadResult.Failure -> WindowUploadResult.Stopped(
            if (upload.isRetryable) ManualBackfillResult.Retrying(pending.batchId, upload.message)
            else ManualBackfillResult.Failure(upload.message),
        )
    }

    private fun confirmUpload(pending: ExportHistoryEntry, zoneId: ZoneId): WindowUploadResult.Confirmed {
        val confirmed = pending.copy(
            status = HistoryBatchStatus.CONFIRMED,
            updatedAt = clock.now(zoneId).toInstant(),
        )
        historyStore.upsert(confirmed)
        pendingStore.remove(destinationKey, pending.batchId)
        return WindowUploadResult.Confirmed(confirmed)
    }

    private fun summarizeBackfill(
        confirmed: List<ExportHistoryEntry>,
        emptyDates: List<LocalDate>,
    ): ManualBackfillResult = if (confirmed.isNotEmpty()) {
        ManualBackfillResult.Success(confirmed)
    } else {
        ManualBackfillResult.NoRecordsFound(emptyDates.distinct())
    }
}
