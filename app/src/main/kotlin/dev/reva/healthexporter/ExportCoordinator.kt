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

interface IdGenerator {
    fun generateId(): String
}

object UuidGenerator : IdGenerator {
    override fun generateId(): String = UUID.randomUUID().toString()
}

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
        // 1. Verify destination readiness
        val destStatus = try {
            destination.verifyConfiguration()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            DestinationStatus.Unavailable("Destination check failed: ${e.message}", e)
        }

        when (destStatus) {
            is DestinationStatus.Ready -> { /* Proceed */ }
            is DestinationStatus.InvalidConfiguration -> {
                return@withLock ExportCycleResult.TerminalFailure(
                    batch = null,
                    message = destStatus.message,
                    cause = destStatus.cause,
                    userActionRequired = true,
                )
            }
            is DestinationStatus.Unavailable -> {
                return@withLock ExportCycleResult.RetryableFailure(
                    batch = null,
                    message = destStatus.message,
                    cause = destStatus.cause,
                )
            }
        }

        // 2. Check for pending batch from earlier interrupted / failed run
        val pendingBatch = try {
            stateStore.getPendingBatch()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            return@withLock ExportCycleResult.TerminalFailure(
                batch = null,
                message = "Failed to load pending batch from state store: ${e.message}",
                cause = e,
            )
        }

        if (pendingBatch != null) {
            return@withLock uploadAndConfirm(batch = pendingBatch, isRetry = true)
        }

        // 3. Determine next export time window
        val now = clock.now(zoneId).toInstant()
        val lastCheckpoint = try {
            stateStore.getLastCheckpoint()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            return@withLock ExportCycleResult.TerminalFailure(
                batch = null,
                message = "Failed to load checkpoint from state store: ${e.message}",
                cause = e,
            )
        }

        val startInclusive = lastCheckpoint?.lastWindowEnd ?: now.minus(config.initialLookbackPeriod)
        var endExclusive = now

        if (config.maxBatchDuration != null) {
            val clamped = startInclusive.plus(config.maxBatchDuration)
            if (clamped.isBefore(endExclusive)) {
                endExclusive = clamped
            }
        }

        if (!startInclusive.isBefore(endExclusive)) {
            return@withLock ExportCycleResult.NothingToExport(
                timeWindow = null,
                message = "No new export window available (start $startInclusive is not before end $endExclusive)",
            )
        }

        val timeWindow = TimeWindow(startInclusive = startInclusive, endExclusive = endExclusive)

        // 4. Query records from Health Connect
        val rawRecords = try {
            recordReader.readRecords(timeWindow)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (security: SecurityException) {
            return@withLock ExportCycleResult.TerminalFailure(
                batch = null,
                message = "Health Connect read permission denied: ${security.message ?: "permission revoked"}",
                cause = security,
                userActionRequired = true,
            )
        } catch (e: Exception) {
            return@withLock ExportCycleResult.RetryableFailure(
                batch = null,
                message = "Failed reading Health Connect records: ${e.message ?: "read error"}",
                cause = e,
            )
        }

        // 5. Deduplicate and construct immutable batch
        val deduplicated = rawRecords.distinctBy { it.recordType to (it.metadata.clientRecordId ?: it.metadata.recordId) }
        val installationId = stateStore.getInstallationId()
        val batchId = idGenerator.generateId()
        val recordTypes = deduplicated.map { it.recordType }.distinct().sorted()

        val header = BatchHeader(
            schemaVersion = BatchHeader.CURRENT_SCHEMA_VERSION,
            installationId = installationId,
            batchId = batchId,
            createdAt = now,
            timeWindow = timeWindow,
            recordCount = deduplicated.size,
            recordTypes = recordTypes,
        )

        val batch = ExportBatch(
            header = header,
            records = deduplicated,
        )

        // 6. Persist pending batch before attempting upload
        try {
            stateStore.savePendingBatch(batch)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            return@withLock ExportCycleResult.RetryableFailure(
                batch = batch,
                message = "Failed to persist pending batch before upload: ${e.message}",
                cause = e,
            )
        }

        // 7. Upload and advance checkpoint
        return@withLock uploadAndConfirm(batch = batch, isRetry = false)
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
