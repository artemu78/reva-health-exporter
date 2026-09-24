package dev.reva.healthexporter

import java.time.Instant

interface EmaNotificationGateway {
    fun show(eventId: String)
    fun cancel(eventId: String)
}

class EmaPromptHandler(
    private val store: EmaEventStore,
    private val notifications: EmaNotificationGateway,
) {
    fun deliver(eventId: String, now: Instant = Instant.now()): Boolean {
        val event = store.get(eventId) ?: return false
        if (event.status != EmaResponseStatus.PENDING) return false

        val eventComparator = compareBy<EmaEvent>({ it.scheduledAt }, { it.id })
        val pendingEvents = store.all().filter { it.status == EmaResponseStatus.PENDING }

        val laterDuePending = pendingEvents.filter {
            it.id != eventId && eventComparator.compare(it, event) > 0 && !it.scheduledAt.isAfter(now)
        }
        if (laterDuePending.isNotEmpty()) {
            expire(eventId)
            return false
        }

        val earlierPending = pendingEvents.filter {
            it.id != eventId && eventComparator.compare(it, event) < 0
        }
        earlierPending.forEach {
            expire(it.id)
        }

        notifications.show(eventId)
        return true
    }

    fun dismiss(eventId: String): Boolean {
        val updated = EmaCheckInService(store).dismiss(eventId)
        notifications.cancel(eventId)
        return updated
    }

    fun expire(eventId: String): Boolean {
        val updated = EmaCheckInService(store).expire(eventId)
        notifications.cancel(eventId)
        return updated
    }
}
