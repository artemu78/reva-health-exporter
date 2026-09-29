package dev.reva.healthexporter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EmaNotificationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val channelId = "ema-test-${UUID.randomUUID()}"
    private val eventId = "synthetic-${UUID.randomUUID()}"
    private val createdAt = Instant.parse("2026-09-29T10:15:30Z")
    private val gateway = AndroidEmaNotificationGateway(context, { createdAt }, channelId)

    @After
    fun cleanup() {
        gateway.cancel(eventId)
        manager.deleteNotificationChannel(channelId)
    }

    @Test
    fun newChannelUsesSystemNotificationSoundWithoutDndBypass() {
        gateway.show(eventId)

        val channel = manager.getNotificationChannel(channelId)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, channel.sound)
        assertEquals(AudioAttributes.USAGE_NOTIFICATION, channel.audioAttributes.usage)
        assertFalse(channel.canBypassDnd())
    }

    @Test
    fun notificationShowsActualCreationTime() {
        gateway.show(eventId)

        val notification = awaitNotification()
        assertEquals(createdAt.toEpochMilli(), notification.`when`)
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        assertEquals(channelId, notification.channelId)
    }

    @Test
    fun postingPreservesExistingSilentChannel() {
        manager.createNotificationChannel(
            NotificationChannel(channelId, "Synthetic silent channel", NotificationManager.IMPORTANCE_LOW)
                .apply { setSound(null, null) },
        )

        gateway.show(eventId)
        gateway.show(eventId)

        val channel = manager.getNotificationChannel(channelId)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
    }

    private fun awaitNotification(): Notification {
        // NotificationManager queues posting asynchronously in the system process.
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            manager.activeNotifications.firstOrNull {
                it.id == AndroidEmaNotificationGateway.notificationId(eventId)
            }?.let { return it.notification }
            SystemClock.sleep(50)
        }
        throw AssertionError("Synthetic EMA notification was not posted within 5 seconds")
    }
}
