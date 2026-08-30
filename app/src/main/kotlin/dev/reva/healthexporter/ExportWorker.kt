package dev.reva.healthexporter

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ExportWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = execute(
        context = applicationContext,
        clientFactory = clientFactory,
        destinationFactory = destinationFactory,
        stateStoreFactory = stateStoreFactory,
        recordReaderFactory = recordReaderFactory,
        driveTokenProvider = driveTokenProvider,
        clock = clock,
        zoneId = zoneId,
        idGenerator = idGenerator,
    )

    companion object {
        const val KEY_OUTCOME = "outcome"
        const val KEY_BATCH_ID = "batch_id"
        const val KEY_RECORD_COUNT = "record_count"
        const val KEY_EXECUTION_TIME = "execution_time"
        const val KEY_MESSAGE = "message"
        const val KEY_LOCATION = "destination_location"

        val sharedExecutionLock = Mutex()

        var clientFactory: ((Context?) -> HealthConnectClient)? = null
        var destinationFactory: ((Context?) -> ExportDestination)? = null
        var stateStoreFactory: ((Context?) -> ExportStateStore)? = null
        var recordReaderFactory: ((HealthConnectClient) -> HealthExportRecordReader)? = null
        var driveTokenProvider: (suspend (Context?) -> String?)? = null
        var clock: DiagnosticClock = SystemDiagnosticClock
        var zoneId: ZoneId = ZoneId.systemDefault()
        var idGenerator: IdGenerator = UuidGenerator

        fun resetDefaults() {
            clientFactory = null
            destinationFactory = null
            stateStoreFactory = null
            recordReaderFactory = null
            driveTokenProvider = null
            clock = SystemDiagnosticClock
            zoneId = ZoneId.systemDefault()
            idGenerator = UuidGenerator
        }

        suspend fun execute(
            context: Context? = null,
            clientFactory: ((Context?) -> HealthConnectClient)? = this.clientFactory,
            destinationFactory: ((Context?) -> ExportDestination)? = this.destinationFactory,
            stateStoreFactory: ((Context?) -> ExportStateStore)? = this.stateStoreFactory,
            recordReaderFactory: ((HealthConnectClient) -> HealthExportRecordReader)? = this.recordReaderFactory,
            driveTokenProvider: (suspend (Context?) -> String?)? = this.driveTokenProvider,
            clock: DiagnosticClock = this.clock,
            zoneId: ZoneId = this.zoneId,
            idGenerator: IdGenerator = this.idGenerator,
        ): Result = sharedExecutionLock.withLock {
            val now = clock.now(zoneId).toInstant()
            val stateStore = when {
                stateStoreFactory != null -> stateStoreFactory(context)
                context != null -> SharedPreferencesExportStateStore(context)
                else -> InMemoryExportStateStore()
            }

            val client = try {
                when {
                    clientFactory != null -> clientFactory(context)
                    context != null -> HealthConnectClient.getOrCreate(context)
                    else -> error("Context or clientFactory must be provided")
                }
            } catch (e: Exception) {
                val summary = ExportExecutionSummary(
                    outcome = ExportOutcome.USER_ACTION_REQUIRED,
                    message = "Health Connect is unavailable on this device: ${e.message ?: "provider missing"}",
                    executionTimestamp = now,
                )
                stateStore.saveExecutionSummary(summary)
                return@withLock Result.failure(createOutputData(summary))
            }

            val destination = try {
                when {
                    destinationFactory != null -> destinationFactory(context)
                    context != null -> defaultDestination(context, driveTokenProvider)
                    else -> error("Context or destinationFactory must be provided")
                }
            } catch (e: Exception) {
                val summary = ExportExecutionSummary(
                    outcome = ExportOutcome.USER_ACTION_REQUIRED,
                    message = "Failed to configure export destination: ${e.message}",
                    executionTimestamp = now,
                )
                stateStore.saveExecutionSummary(summary)
                return@withLock Result.failure(createOutputData(summary))
            }

            val recordReader = recordReaderFactory?.invoke(client)
                ?: HealthConnectExportReader(client = client)

            val coordinator = ExportCoordinator(
                stateStore = stateStore,
                recordReader = recordReader,
                destination = destination,
                clock = clock,
                zoneId = zoneId,
                idGenerator = idGenerator,
            )

            val cycleResult = coordinator.export()
            val summary = when (cycleResult) {
                is ExportCycleResult.Success -> ExportExecutionSummary(
                    outcome = ExportOutcome.SUCCESS,
                    batchId = cycleResult.batch.header.batchId,
                    recordCount = cycleResult.batch.header.recordCount,
                    executionTimestamp = now,
                    message = "Successfully exported batch ${cycleResult.batch.header.batchId} (${cycleResult.batch.header.recordCount} records)",
                    destinationLocation = cycleResult.destinationLocation,
                )
                is ExportCycleResult.NothingToExport -> ExportExecutionSummary(
                    outcome = ExportOutcome.NOTHING_TO_EXPORT,
                    executionTimestamp = now,
                    message = cycleResult.message,
                )
                is ExportCycleResult.RetryableFailure -> ExportExecutionSummary(
                    outcome = ExportOutcome.RETRYABLE_FAILURE,
                    batchId = cycleResult.batch?.header?.batchId,
                    recordCount = cycleResult.batch?.header?.recordCount ?: 0,
                    executionTimestamp = now,
                    message = cycleResult.message,
                )
                is ExportCycleResult.TerminalFailure -> ExportExecutionSummary(
                    outcome = if (cycleResult.userActionRequired) {
                        ExportOutcome.USER_ACTION_REQUIRED
                    } else {
                        ExportOutcome.TERMINAL_FAILURE
                    },
                    batchId = cycleResult.batch?.header?.batchId,
                    recordCount = cycleResult.batch?.header?.recordCount ?: 0,
                    executionTimestamp = now,
                    message = cycleResult.message,
                )
            }

            stateStore.saveExecutionSummary(summary)
            val outputData = createOutputData(summary)

            return@withLock when (summary.outcome) {
                ExportOutcome.SUCCESS,
                ExportOutcome.NOTHING_TO_EXPORT,
                -> Result.success(outputData)

                ExportOutcome.RETRYABLE_FAILURE -> Result.retry()

                ExportOutcome.USER_ACTION_REQUIRED,
                ExportOutcome.TERMINAL_FAILURE,
                -> Result.failure(outputData)
            }
        }

        private fun defaultDestination(
            context: Context,
            tokenProvider: (suspend (Context?) -> String?)?,
        ): ExportDestination {
            val gateway = HttpGoogleDriveGateway(
                tokenProvider = {
                    tokenProvider?.invoke(context)
                },
            )
            return GoogleDriveDestination(driveGateway = gateway)
        }

        private fun createOutputData(summary: ExportExecutionSummary) = workDataOf(
            KEY_OUTCOME to summary.outcome.name,
            KEY_BATCH_ID to summary.batchId,
            KEY_RECORD_COUNT to summary.recordCount,
            KEY_EXECUTION_TIME to summary.executionTimestamp.toString(),
            KEY_MESSAGE to summary.message,
            KEY_LOCATION to summary.destinationLocation,
        )
    }
}
