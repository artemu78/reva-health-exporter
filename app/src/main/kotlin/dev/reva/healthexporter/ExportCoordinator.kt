package dev.reva.healthexporter

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ExportCoordinatorConfig(
    val initialLookbackPeriod: Duration = Duration.ofDays(1),
    val maxBatchDuration: Duration? = Duration.ofDays(1),
)

fun interface IdGenerator {
    fun generateId(): String
}

val UuidGenerator = IdGenerator { UUID.randomUUID().toString() }

sealed interface ExportCycleResult {
    data class Success(
        val batch: ExportBatch,
        val checkpoint: ExportCheckpoint,
        val isRetry: Boolean,
        val destinationLocation: String? = null,
    ) : ExportCycleResult

    data class NothingToExport(
        val timeWindow: TimeWindow?,
        val message: String,
    ) : ExportCycleResult

    data class RetryableFailure(
        val batch: ExportBatch?,
        val message: String,
        val cause: Throwable? = null,
    ) : ExportCycleResult

    data class TerminalFailure(
        val batch: ExportBatch?,
        val message: String,
        val cause: Throwable? = null,
        val userActionRequired: Boolean = false,
    ) : ExportCycleResult
}

class ExportCoordinator(
    private val stateStore: ExportStateStore,
    private val recordReader: HealthExportRecordReader,
    private val destination: ExportDestination,
    private val clock: DiagnosticClock = SystemDiagnosticClock,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val idGenerator: IdGenerator = UuidGenerator,
    private val config: ExportCoordinatorConfig = ExportCoordinatorConfig(),
) {
    private val mutex = Mutex()

    suspend fun export(): ExportCycleResult = mutex.withLock {
        val destCheckFailure = verifyDestination()
        if (destCheckFailure != null) {
            return@withLock destCheckFailure
        }

        val (pendingBatch, pendingBatchFailure) = loadPendingBatch()
        if (pendingBatchFailure != null) {
            return@withLock pendingBatchFailure
        }
        if (pendingBatch != null) {
            return@withLock uploadAndConfirm(batch = pendingBatch, isRetry = true)
        }

        val now = clock.now(zoneId).toInstant()
        val (timeWindow, windowFailure) = resolveTimeWindow(now)
        if (windowFailure != null) {
            return@withLock windowFailure
        }
        val safeWindow = checkNotNull(timeWindow)

        val (rawRecords, readFailure) = readConfirmedRecords(safeWindow)
        if (readFailure != null) {
            return@withLock readFailure
        }

        val batch = buildExportBatch(now, safeWindow, checkNotNull(rawRecords))
        return@withLock persistAndUpload(batch)
    }

    private suspend fun verifyDestination(): ExportCycleResult? {
        val destStatus = try {
            destination.verifyConfiguration()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            DestinationStatus.Unavailable("Destination check failed: ${e.message}", e)
        }

        return when (destStatus) {
            is DestinationStatus.Ready -> null
            is DestinationStatus.InvalidConfiguration -> ExportCycleResult.TerminalFailure(
                batch = null,
                message = destStatus.message,
                cause = destStatus.cause,
                userActionRequired = true,
            )
            is DestinationStatus.Unavailable -> ExportCycleResult.RetryableFailure(
                batch = null,
                message = destStatus.message,
                cause = destStatus.cause,
            )
        }
    }

    private fun loadPendingBatch(): Pair<ExportBatch?, ExportCycleResult?> {
        return try {
            val pending = stateStore.getPendingBatch()
            Pair(pending, null)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Pair(
                null,
                ExportCycleResult.TerminalFailure(
                    batch = null,
                    message = "Failed to load pending batch from state store: ${e.message}",
                    cause = e,
                ),
            )
        }
    }

    private fun resolveTimeWindow(now: Instant): Pair<TimeWindow?, ExportCycleResult?> {
        val lastCheckpoint = try {
            stateStore.getLastCheckpoint()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            return Pair(
                null,
                ExportCycleResult.TerminalFailure(
                    batch = null,
                    message = "Failed to load checkpoint from state store: ${e.message}",
                    cause = e,
                ),
            )
        }

        val localNow = now.atZone(zoneId)
        val today = localNow.toLocalDate()

        val startInclusive = if (lastCheckpoint != null) {
            val checkpointInstant = lastCheckpoint.lastWindowEnd
            val checkpointDate = checkpointInstant.atZone(zoneId).toLocalDate()
            val dayStart = checkpointDate.atStartOfDay(zoneId).toInstant()
            if (checkpointInstant == dayStart || checkpointInstant == checkpointDate.plusDays(1).atStartOfDay(zoneId).toInstant()) {
                checkpointInstant
            } else {
                dayStart
            }
        } else {
            val lookbackDays = maxOf(1L, (config.initialLookbackPeriod.toHours() + 23) / 24)
            today.minusDays(lookbackDays).atStartOfDay(zoneId).toInstant()
        }

        val startDate = startInclusive.atZone(zoneId).toLocalDate()
        val maxDuration = config.maxBatchDuration
        val maxDays = if (maxDuration != null) maxOf(1L, (maxDuration.toHours() + 23) / 24) else null
        val targetEndDate = if (maxDays != null) startDate.plusDays(maxDays) else today.plusDays(1)
        val endDate = if (targetEndDate.isAfter(today.plusDays(1))) today.plusDays(1) else targetEndDate
        val endExclusive = endDate.atStartOfDay(zoneId).toInstant()

        if (!startInclusive.isBefore(endExclusive)) {
            return Pair(
                null,
                ExportCycleResult.NothingToExport(
                    timeWindow = null,
                    message = "No new export window available (start $startInclusive is not before end $endExclusive)",
                ),
            )
        }

        return Pair(TimeWindow(startInclusive = startInclusive, endExclusive = endExclusive), null)
    }

    private suspend fun readConfirmedRecords(timeWindow: TimeWindow): Pair<List<CanonicalRecord>?, ExportCycleResult?> {
        return try {
            val records = recordReader.readRecords(timeWindow)
            Pair(records, null)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (security: SecurityException) {
            Pair(
                null,
                ExportCycleResult.TerminalFailure(
                    batch = null,
                    message = "Health Connect read permission denied: ${security.message ?: "permission revoked"}",
                    cause = security,
                    userActionRequired = true,
                ),
            )
        } catch (e: Exception) {
            Pair(
                null,
                ExportCycleResult.RetryableFailure(
                    batch = null,
                    message = "Failed reading Health Connect records: ${e.message ?: "read error"}",
                    cause = e,
                ),
            )
        }
    }

    private fun buildExportBatch(
        now: Instant,
        timeWindow: TimeWindow,
        rawRecords: List<CanonicalRecord>,
    ): ExportBatch {
        val deduplicated = ExportRecordCanonicalizer.canonicalize(rawRecords)
        val installationId = stateStore.getInstallationId()
        val recordTypes = deduplicated.map { it.recordType }.distinct().sorted()

        val startDate = timeWindow.startInclusive.atZone(zoneId).toLocalDate()
        val isSingleDay = localDayWindow(startDate, zoneId) == timeWindow
        val accountId = (destination as? GoogleDriveDestination)?.driveGateway?.accountId
        val snapshotKey = if (isSingleDay) dailySnapshotKey(destination.destinationName, accountId, zoneId, startDate) else null
        val batchId = snapshotKey?.identity ?: idGenerator.generateId()
        val exportDate = if (isSingleDay) startDate.toString() else null
        val exportTimezone = if (isSingleDay) zoneId.id else null
        val dailyIdentity = snapshotKey?.identity

        val header = BatchHeader(
            schemaVersion = BatchHeader.CURRENT_SCHEMA_VERSION,
            installationId = installationId,
            batchId = batchId,
            createdAt = now,
            timeWindow = timeWindow,
            recordCount = deduplicated.size,
            recordTypes = recordTypes,
            exportDate = exportDate,
            exportTimezone = exportTimezone,
            dailyIdentity = dailyIdentity,
        )

        return ExportBatch(
            header = header,
            records = deduplicated,
        )
    }

    private suspend fun persistAndUpload(batch: ExportBatch): ExportCycleResult {
        try {
            stateStore.savePendingBatch(batch)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            return ExportCycleResult.RetryableFailure(
                batch = batch,
                message = "Failed to persist pending batch before upload: ${e.message}",
                cause = e,
            )
        }

        return uploadAndConfirm(batch = batch, isRetry = false)
    }

    private suspend fun uploadAndConfirm(batch: ExportBatch, isRetry: Boolean): ExportCycleResult {
        val uploadResult = try {
            destination.upload(batch)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            UploadResult.Failure(
                message = "Destination upload threw exception: ${e.message}",
                isRetryable = true,
                cause = e,
            )
        }

        return when (uploadResult) {
            is UploadResult.Success -> {
                val previousCheckpoint = stateStore.getLastCheckpoint()
                val totalCount = (previousCheckpoint?.totalRecordCount ?: 0L) + batch.header.recordCount
                val newCheckpoint = ExportCheckpoint(
                    lastWindowEnd = batch.header.timeWindow.endExclusive,
                    lastBatchId = batch.header.batchId,
                    exportedAt = clock.now(zoneId).toInstant(),
                    totalRecordCount = totalCount,
                )

                try {
                    stateStore.saveCheckpoint(newCheckpoint)
                    stateStore.clearPendingBatch()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Exception) {
                    return ExportCycleResult.RetryableFailure(
                        batch = batch,
                        message = "Upload succeeded but failed to save checkpoint or clear pending batch: ${e.message}",
                        cause = e,
                    )
                }

                ExportCycleResult.Success(
                    batch = batch,
                    checkpoint = newCheckpoint,
                    isRetry = isRetry,
                    destinationLocation = uploadResult.location,
                )
            }
            is UploadResult.Failure -> {
                if (uploadResult.isRetryable) {
                    ExportCycleResult.RetryableFailure(
                        batch = batch,
                        message = uploadResult.message,
                        cause = uploadResult.cause,
                    )
                } else {
                    ExportCycleResult.TerminalFailure(
                        batch = batch,
                        message = uploadResult.message,
                        cause = uploadResult.cause,
                    )
                }
            }
        }
    }
}
