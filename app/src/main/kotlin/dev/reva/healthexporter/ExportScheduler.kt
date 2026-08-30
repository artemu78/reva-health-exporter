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
    const val KEY_ACCOUNT_ID = "account_id"

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
        accountId: String? = null,
    ): PeriodicWorkRequest {
        val intervalMillis = interval.toMillis().coerceAtLeast(PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS)
        val builder = if (flexInterval != null) {
            val flexMillis = flexInterval.toMillis()
                .coerceAtLeast(PeriodicWorkRequest.MIN_PERIODIC_FLEX_MILLIS)
                .coerceAtMost(intervalMillis)
            PeriodicWorkRequestBuilder<ExportWorker>(intervalMillis, TimeUnit.MILLISECONDS, flexMillis, TimeUnit.MILLISECONDS)
        } else {
            PeriodicWorkRequestBuilder<ExportWorker>(intervalMillis, TimeUnit.MILLISECONDS)
        }

        if (accountId != null) {
            builder.setInputData(androidx.work.workDataOf(KEY_ACCOUNT_ID to accountId))
        }

        return builder
            .setConstraints(createConstraints())
            .setBackoffCriteria(DEFAULT_BACKOFF_POLICY, DEFAULT_BACKOFF_DELAY_MILLIS, TimeUnit.MILLISECONDS)
            .addTag(TAG_EXPORT_WORK)
            .build()
    }

    fun buildOneTimeWorkRequest(accountId: String? = null): OneTimeWorkRequest {
        val builder = OneTimeWorkRequestBuilder<ExportWorker>()
        if (accountId != null) {
            builder.setInputData(androidx.work.workDataOf(KEY_ACCOUNT_ID to accountId))
        }
        return builder
            .setConstraints(createConstraints())
            .setBackoffCriteria(DEFAULT_BACKOFF_POLICY, DEFAULT_BACKOFF_DELAY_MILLIS, TimeUnit.MILLISECONDS)
            .addTag(TAG_EXPORT_WORK)
            .build()
    }

    fun schedulePeriodicExport(
        context: Context,
        interval: Duration = DEFAULT_PERIODIC_INTERVAL,
        policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP,
        accountId: String? = null,
    ): Operation = schedulePeriodicExport(WorkManager.getInstance(context), interval, policy, accountId)

    fun schedulePeriodicExport(
        workManager: WorkManager,
        interval: Duration = DEFAULT_PERIODIC_INTERVAL,
        policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP,
        accountId: String? = null,
    ): Operation {
        val request = buildPeriodicWorkRequest(interval, accountId = accountId)
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
        accountId: String? = null,
    ): UUID = triggerImmediateExport(WorkManager.getInstance(context), policy, accountId)

    fun triggerImmediateExport(
        workManager: WorkManager,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
        accountId: String? = null,
    ): UUID {
        val request = buildOneTimeWorkRequest(accountId)
        workManager.enqueueUniqueWork(
            ONE_TIME_WORK_NAME,
            policy,
            request,
        )
        return request.id
    }
}
