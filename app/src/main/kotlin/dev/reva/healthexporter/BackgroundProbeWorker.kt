package dev.reva.healthexporter

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.ZoneId

class BackgroundProbeWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = execute(
        context = applicationContext,
        clientFactory = clientFactory,
        readerFactory = readerFactory,
        storeFactory = storeFactory,
        clock = clock,
        zoneId = zoneId,
    )

    companion object {
        const val WORK_NAME = "background_health_connect_probe"
        const val KEY_OUTCOME = "outcome"
        const val KEY_MESSAGE = "message"
        const val KEY_TOTAL_RECORDS = "total_records"
        const val KEY_READ_TYPES_COUNT = "read_types_count"
        const val KEY_EXECUTION_TIME = "execution_time"
        const val KEY_DATA_ORIGINS = "data_origins"

        var clientFactory: ((Context?) -> HealthConnectClient)? = null
        var readerFactory: ((HealthConnectClient) -> BackgroundHealthRecordReader)? = null
        var storeFactory: ((Context?) -> BackgroundProbeStore)? = null
        var clock: DiagnosticClock = SystemDiagnosticClock
        var zoneId: ZoneId = ZoneId.systemDefault()

        fun resetDefaults() {
            clientFactory = null
            readerFactory = null
            storeFactory = null
            clock = SystemDiagnosticClock
            zoneId = ZoneId.systemDefault()
        }

        suspend fun execute(
            context: Context? = null,
            clientFactory: ((Context?) -> HealthConnectClient)? = this.clientFactory,
            readerFactory: ((HealthConnectClient) -> BackgroundHealthRecordReader)? = this.readerFactory,
            storeFactory: ((Context?) -> BackgroundProbeStore)? = this.storeFactory,
            clock: DiagnosticClock = this.clock,
            zoneId: ZoneId = this.zoneId,
        ): Result {
            val now = clock.now(zoneId)
            val window = ProbeTimeWindow.previousLocalDays(now, days = 1)

            val client = when {
                clientFactory != null -> clientFactory(context)
                context != null -> HealthConnectClient.getOrCreate(context)
                else -> error("Context or clientFactory must be provided")
            }

            val reader = readerFactory?.invoke(client)
                ?: BackgroundHealthRecordReader(client = client, clock = clock, zoneId = zoneId)

            val summary = reader.readConfirmedRecords(window)

            val store = when {
                storeFactory != null -> storeFactory(context)
                context != null -> SharedPreferencesBackgroundProbeStore(context)
                else -> null
            }
            store?.saveSummary(summary)

            val outputData = workDataOf(
                KEY_OUTCOME to summary.outcome.name,
                KEY_MESSAGE to summary.message,
                KEY_TOTAL_RECORDS to summary.totalRecords,
                KEY_READ_TYPES_COUNT to summary.readTypesCount,
                KEY_EXECUTION_TIME to summary.executionTimestamp?.toString(),
                KEY_DATA_ORIGINS to summary.dataOrigins.toTypedArray(),
            )

            return when (summary.outcome) {
                BackgroundReadOutcome.SUCCESS -> Result.success(outputData)
                BackgroundReadOutcome.RETRYABLE_FAILURE -> Result.retry()
                BackgroundReadOutcome.USER_ACTION_REQUIRED,
                BackgroundReadOutcome.UNSUPPORTED,
                -> Result.failure(outputData)
            }
        }
    }
}
