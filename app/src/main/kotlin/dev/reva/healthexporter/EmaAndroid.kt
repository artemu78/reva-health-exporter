package dev.reva.healthexporter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.provider.Settings
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

fun emaEventStore(context: Context): EmaEventStore =
    FileEmaEventStore(java.io.File(context.filesDir, "ema/events"))

class WorkManagerEmaGateway(
    private val workManager: WorkManager,
    private val now: () -> Instant = Instant::now,
) : EmaWorkGateway {
    constructor(context: Context) : this(WorkManager.getInstance(context))

    override fun enqueuePrompt(prompt: EmaScheduledPrompt) {
        val promptData = Data.Builder().putString(KEY_EVENT_ID, prompt.eventId).build()
        val promptRequest = OneTimeWorkRequestBuilder<EmaPromptWorker>()
            .setInputData(promptData)
            .setInitialDelay(delayUntil(prompt.scheduledAt), TimeUnit.MILLISECONDS)
            .addTag(TAG_EMA_WORK)
            .build()
        workManager.enqueueUniqueWork(
            "$PROMPT_WORK_PREFIX${prompt.eventId}",
            ExistingWorkPolicy.KEEP,
            promptRequest,
        )
    }

    override fun ensureScheduleRefresh() {
        val request = PeriodicWorkRequestBuilder<EmaScheduleRefreshWorker>(12, TimeUnit.HOURS)
            .addTag(TAG_EMA_WORK)
            .build()
        workManager.enqueueUniquePeriodicWork(
            REFRESH_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    override fun cancelAll() {
        workManager.cancelAllWorkByTag(TAG_EMA_WORK)
    }

    private fun delayUntil(instant: Instant): Long =
        Duration.between(now(), instant).toMillis().coerceAtLeast(0L)

    companion object {
        const val KEY_EVENT_ID = "ema_event_id"
        const val TAG_EMA_WORK = "reva_ema_work"
        const val PROMPT_WORK_PREFIX = "reva_ema_prompt_"
        const val REFRESH_WORK_NAME = "reva_ema_schedule_refresh"
    }
}

class EmaPromptWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val eventId = inputData.getString(WorkManagerEmaGateway.KEY_EVENT_ID) ?: return Result.failure()
        EmaPromptHandler(
            emaEventStore(applicationContext),
            AndroidEmaNotificationGateway(applicationContext),
        ).deliver(eventId, Instant.now())
        return Result.success()
    }
}

class EmaScheduleRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val config = SharedPreferencesEmaConfigStore(applicationContext).load()
        val store = emaEventStore(applicationContext)
        val notifications = AndroidEmaNotificationGateway(applicationContext)
        EmaScheduleCoordinator(
            store = store,
            workGateway = WorkManagerEmaGateway(applicationContext),
            promptHandler = EmaPromptHandler(store, notifications),
        ).reconcile(Instant.now(), ZoneId.systemDefault(), config)
        return Result.success()
    }
}

class AndroidEmaNotificationGateway(
    private val context: Context,
    private val now: () -> Instant = Instant::now,
    private val channelId: String = CHANNEL_ID,
) : EmaNotificationGateway {
    private val manager = context.getSystemService(NotificationManager::class.java)

    override fun show(eventId: String) {
        ensureChannel()
        val openIntent = Intent(context, EmaCheckInActivity::class.java)
            .putExtra(WorkManagerEmaGateway.KEY_EVENT_ID, eventId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val dismissIntent = Intent(context, EmaDismissReceiver::class.java)
            .putExtra(WorkManagerEmaGateway.KEY_EVENT_ID, eventId)
        val requestCode = eventId.hashCode()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val dismissPendingIntent = PendingIntent.getBroadcast(context, requestCode, dismissIntent, flags)
        val notification = Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_ema_notification)
            .setContentTitle(context.getString(R.string.ema_notification_title))
            .setContentText(context.getString(R.string.ema_notification_text))
            .setContentIntent(PendingIntent.getActivity(context, requestCode, openIntent, flags))
            .setDeleteIntent(dismissPendingIntent)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_ema_notification),
                    context.getString(R.string.ema_dismiss),
                    dismissPendingIntent,
                ).build(),
            )
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setWhen(now().toEpochMilli())
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        manager.notify(notificationId(eventId), notification)
    }

    override fun cancel(eventId: String) {
        manager.cancel(notificationId(eventId))
    }

    private fun ensureChannel() {
        manager.createNotificationChannel(
            NotificationChannel(
                channelId,
                context.getString(R.string.ema_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.ema_notification_channel_description)
                // Keep the symbolic default URI so Android follows the system sound selection.
                // Re-registering this channel preserves the user's existing channel preferences.
                setSound(
                    Settings.System.DEFAULT_NOTIFICATION_URI,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            },
        )
    }

    companion object {
        const val CHANNEL_ID = "ema_check_ins"
        fun notificationId(eventId: String): Int = eventId.hashCode() and Int.MAX_VALUE
    }
}

class EmaDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getStringExtra(WorkManagerEmaGateway.KEY_EVENT_ID) ?: return
        EmaPromptHandler(
            emaEventStore(context),
            AndroidEmaNotificationGateway(context),
        ).dismiss(eventId)
    }
}
