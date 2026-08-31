package dev.reva.healthexporter

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ExportWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val accountId = inputData.getString(ExportScheduler.KEY_ACCOUNT_ID)
        return execute(applicationContext, accountId)
    }

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
        var driveTokenProviderFactory: (Context) -> suspend () -> String? = { context ->
            val provider = GoogleDriveAccessTokenProvider(context)
            provider::getAccessToken
        }
        var driveTransportFactory: () -> HttpTransport = ::DefaultHttpTransport
        var clock: DiagnosticClock = SystemDiagnosticClock
        var zoneId: ZoneId = ZoneId.systemDefault()
        var idGenerator: IdGenerator = UuidGenerator

        fun resetDefaults() {
            clientFactory = null
            destinationFactory = null
            stateStoreFactory = null
            recordReaderFactory = null
            driveTokenProviderFactory = { context ->
                val provider = GoogleDriveAccessTokenProvider(context)
                provider::getAccessToken
            }
            driveTransportFactory = ::DefaultHttpTransport
            clock = SystemDiagnosticClock
            zoneId = ZoneId.systemDefault()
            idGenerator = UuidGenerator
        }

        suspend fun execute(
            context: Context? = null,
            accountId: String? = null,
        ): Result = sharedExecutionLock.withLock {
            val now = clock.now(zoneId).toInstant()
            val stateStore = resolveStateStore(context)

            val client = when (val res = resolveClient(context, now, stateStore)) {
                is Resolution.Success -> res.value
                is Resolution.Failure -> return@withLock Result.failure(createOutputData(res.summary))
            }

            val destination = when (val res = resolveDestination(context, now, stateStore, accountId)) {
                is Resolution.Success -> res.value
                is Resolution.Failure -> return@withLock Result.failure(createOutputData(res.summary))
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

            val summary = mapCycleResultToSummary(coordinator.export(), now)
            stateStore.saveExecutionSummary(summary)
            return@withLock mapSummaryToWorkerResult(summary)
        }

        private sealed interface Resolution<out T> {
            data class Success<T>(val value: T) : Resolution<T>
            data class Failure(val summary: ExportExecutionSummary) : Resolution<Nothing>
        }

        private fun resolveStateStore(context: Context?): ExportStateStore = when {
            stateStoreFactory != null -> stateStoreFactory!!(context)
            context != null -> SharedPreferencesExportStateStore(context)
            else -> InMemoryExportStateStore()
        }

        private fun resolveClient(
            context: Context?,
            now: Instant,
            stateStore: ExportStateStore,
        ): Resolution<HealthConnectClient> = try {
            val client = when {
                clientFactory != null -> clientFactory!!(context)
                context != null -> HealthConnectClient.getOrCreate(context)
                else -> error("Context or clientFactory must be provided")
            }
            Resolution.Success(client)
        } catch (e: Exception) {
            val summary = ExportExecutionSummary(
                outcome = ExportOutcome.USER_ACTION_REQUIRED,
                message = "Health Connect is unavailable on this device: ${e.message ?: "provider missing"}",
                executionTimestamp = now,
            )
            stateStore.saveExecutionSummary(summary)
            Resolution.Failure(summary)
        }

        private fun resolveDestination(
            context: Context?,
            now: Instant,
            stateStore: ExportStateStore,
            accountId: String?,
        ): Resolution<ExportDestination> = try {
            val destination = when {
                destinationFactory != null -> destinationFactory!!(context)
                context != null -> defaultDestination(context, accountId)
                else -> error("Context or destinationFactory must be provided")
            }
            Resolution.Success(destination)
        } catch (e: Exception) {
            val summary = ExportExecutionSummary(
                outcome = ExportOutcome.USER_ACTION_REQUIRED,
                message = "Failed to configure export destination: ${e.message}",
                executionTimestamp = now,
            )
            stateStore.saveExecutionSummary(summary)
            Resolution.Failure(summary)
        }

        private fun mapCycleResultToSummary(
            cycleResult: ExportCycleResult,
            now: Instant,
        ): ExportExecutionSummary = when (cycleResult) {
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

        private fun mapSummaryToWorkerResult(summary: ExportExecutionSummary): Result = when (summary.outcome) {
            ExportOutcome.SUCCESS,
            ExportOutcome.NOTHING_TO_EXPORT,
            -> Result.success(createOutputData(summary))

            ExportOutcome.RETRYABLE_FAILURE -> Result.retry()

            ExportOutcome.USER_ACTION_REQUIRED,
            ExportOutcome.TERMINAL_FAILURE,
            -> Result.failure(createOutputData(summary))
        }

        private fun defaultDestination(
            context: Context,
            accountId: String?,
        ): ExportDestination {
            val tokenProvider = driveTokenProviderFactory(context)
            val gateway = HttpGoogleDriveGateway(
                accountId = accountId,
                tokenProvider = tokenProvider,
                transport = driveTransportFactory(),
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
