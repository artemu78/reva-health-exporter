package dev.reva.healthexporter

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

object ExportScheduler {
    const val PERIODIC_WORK_NAME = "reva_periodic_health_export"
    const val ONE_TIME_WORK_NAME = "reva_immediate_health_export"
    const val TAG_EXPORT_WORK = "reva_export_work"

    val DEFAULT_PERIODIC_INTERVAL: Duration = Duration.ofHours(1)
    val DEFAULT_BACKOFF_POLICY: BackoffPolicy = BackoffPolicy.EXPONENTIAL
    val DEFAULT_BACKOFF_DELAY_MILLIS: Long = WorkRequest.MIN_BACKOFF_MILLIS

    fun createConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    fun buildPeriodicWorkRequest(
        interval: Duration = DEFAULT_PERIODIC_INTERVAL,
        flexInterval: Duration? = null,
    ): PeriodicWorkRequest {
        val intervalMillis = interval.toMillis().coerceAtLeast(PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS)
        val builder = if (flexInterval != null) {
            val flexMillis = flexInterval.toMillis().coerceAtLeast(PeriodicWorkRequest.MIN_PERIODIC_FLEX_MILLIS)
            PeriodicWorkRequestBuilder<ExportWorker>(intervalMillis, TimeUnit.MILLISECONDS, flexMillis, TimeUnit.MILLISECONDS)
        } else {
            PeriodicWorkRequestBuilder<ExportWorker>(intervalMillis, TimeUnit.MILLISECONDS)
        }

        return builder
            .setConstraints(createConstraints())
            .setBackoffCriteria(DEFAULT_BACKOFF_POLICY, DEFAULT_BACKOFF_DELAY_MILLIS, TimeUnit.MILLISECONDS)
            .addTag(TAG_EXPORT_WORK)
            .build()
    }

    fun buildOneTimeWorkRequest(): OneTimeWorkRequest {
        return OneTimeWorkRequestBuilder<ExportWorker>()
            .setConstraints(createConstraints())
            .setBackoffCriteria(DEFAULT_BACKOFF_POLICY, DEFAULT_BACKOFF_DELAY_MILLIS, TimeUnit.MILLISECONDS)
            .addTag(TAG_EXPORT_WORK)
            .build()
    }

    fun schedulePeriodicExport(
        context: Context,
        interval: Duration = DEFAULT_PERIODIC_INTERVAL,
        policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP,
    ): Operation = schedulePeriodicExport(WorkManager.getInstance(context), interval, policy)

    fun schedulePeriodicExport(
        workManager: WorkManager,
        interval: Duration = DEFAULT_PERIODIC_INTERVAL,
        policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP,
    ): Operation {
        val request = buildPeriodicWorkRequest(interval)
        return workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            policy,
            request,
        )
    }

    fun cancelPeriodicExport(context: Context): Operation =
        cancelPeriodicExport(WorkManager.getInstance(context))

    fun cancelPeriodicExport(workManager: WorkManager): Operation {
        return workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    fun triggerImmediateExport(
        context: Context,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
    ): UUID = triggerImmediateExport(WorkManager.getInstance(context), policy)

    fun triggerImmediateExport(
        workManager: WorkManager,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
    ): UUID {
        val request = buildOneTimeWorkRequest()
        workManager.enqueueUniqueWork(
            ONE_TIME_WORK_NAME,
            policy,
            request,
        )
        return request.id
    }
}
