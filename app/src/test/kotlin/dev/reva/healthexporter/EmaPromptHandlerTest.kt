package dev.reva.healthexporter

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmaPromptHandlerTest {
    private val scheduledAt = Instant.parse("2026-09-15T09:00:00Z")

    @Test
    fun pendingPromptInsideResponseWindowShowsOneNotification() {
        val store = InMemoryEmaEventStore().apply {
            save(EmaEvent.pending("event-1", scheduledAt, ZoneId.of("UTC")))
        }
        val notifications = RecordingEmaNotificationGateway()

        val delivered = EmaPromptHandler(store, notifications).deliver(
            eventId = "event-1",
            now = scheduledAt.plusSeconds(30),
        )

        assertTrue(delivered)
        assertEquals(listOf("event-1"), notifications.shown)
    }

    @Test
    fun delayedPromptDoesNotExpireAndShowsNotification() {
        val store = InMemoryEmaEventStore().apply {
            save(EmaEvent.pending("event-2", scheduledAt, ZoneId.of("UTC")))
        }
        val notifications = RecordingEmaNotificationGateway()

        val delivered = EmaPromptHandler(store, notifications).deliver(
            eventId = "event-2",
            now = scheduledAt.plus(Duration.ofHours(5)),
        )

        assertTrue(delivered)
        assertEquals(listOf("event-2"), notifications.shown)
        assertEquals(EmaResponseStatus.PENDING, store.get("event-2")?.status)
    }

    @Test
    fun dismissalMarksPendingEventAndCancelsItsNotification() {
        val store = InMemoryEmaEventStore().apply {
            save(EmaEvent.pending("event-3", scheduledAt, ZoneId.of("UTC")))
        }
        val notifications = RecordingEmaNotificationGateway()

        EmaPromptHandler(store, notifications).dismiss("event-3")

        assertEquals(EmaResponseStatus.DISMISSED, store.get("event-3")?.status)
        assertEquals(listOf("event-3"), notifications.cancelled)
    }

    @Test
    fun supersedesEarlierPendingPromptWhenLaterPromptIsDelivered() {
        val store = InMemoryEmaEventStore().apply {
            save(EmaEvent.pending("event-early", scheduledAt, ZoneId.of("UTC")))
            save(EmaEvent.pending("event-late", scheduledAt.plusSeconds(3600), ZoneId.of("UTC")))
        }
        val notifications = RecordingEmaNotificationGateway()
        val handler = EmaPromptHandler(store, notifications)

        val deliveredLate = handler.deliver(
            eventId = "event-late",
            now = scheduledAt.plusSeconds(3600),
        )

        assertTrue(deliveredLate)
        assertEquals(listOf("event-late"), notifications.shown)
        assertEquals(listOf("event-early"), notifications.cancelled)
        assertEquals(EmaResponseStatus.EXPIRED, store.get("event-early")?.status)
        assertEquals(EmaResponseStatus.PENDING, store.get("event-late")?.status)
    }

    @Test
    fun doesNotDeliverStalePromptWhenLaterPromptAlreadyDue() {
        val store = InMemoryEmaEventStore().apply {
            save(EmaEvent.pending("event-early", scheduledAt, ZoneId.of("UTC")))
            save(EmaEvent.pending("event-late", scheduledAt.plusSeconds(3600), ZoneId.of("UTC")))
        }
        val notifications = RecordingEmaNotificationGateway()
        val handler = EmaPromptHandler(store, notifications)

        val deliveredEarly = handler.deliver(
            eventId = "event-early",
            now = scheduledAt.plusSeconds(3600),
        )

        assertTrue(!deliveredEarly)
        assertTrue(notifications.shown.isEmpty())
        assertEquals(EmaResponseStatus.EXPIRED, store.get("event-early")?.status)
    }
}

private class RecordingEmaNotificationGateway : EmaNotificationGateway {
    val shown = mutableListOf<String>()
    val cancelled = mutableListOf<String>()

    override fun show(eventId: String) {
        shown += eventId
    }

    override fun cancel(eventId: String) {
        cancelled += eventId
    }
}
