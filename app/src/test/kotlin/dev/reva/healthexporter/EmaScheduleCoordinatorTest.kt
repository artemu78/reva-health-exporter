package dev.reva.healthexporter

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmaScheduleCoordinatorTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val now = Instant.parse("2026-09-15T05:00:00Z") // 08:00 local
    private val config = EmaConfig(
        activeStart = LocalTime.of(9, 0),
        activeEnd = LocalTime.of(22, 0),
        checkInsPerDay = 5,
        notificationsEnabled = true,
    )

    @Test
    fun createsAndEnqueuesTodayAndTomorrowWithoutDuplicates() {
        val store = InMemoryEmaEventStore()
        val gateway = RecordingEmaWorkGateway()
        var nextId = 0
        val coordinator = EmaScheduleCoordinator(
            store = store,
            workGateway = gateway,
            planner = EmaSchedulePlanner { 0.5 },
            idGenerator = EmaIdGenerator { "event-${++nextId}" },
        )

        coordinator.reconcile(now, zone, config)
        coordinator.reconcile(now, zone, config)

        assertEquals(10, store.all().size)
        assertEquals(10, gateway.prompts.map { it.eventId }.distinct().size)
        assertTrue(gateway.prompts.all { it.expiresAt == it.scheduledAt.plus(Duration.ofHours(2)) })
        assertEquals(2, gateway.refreshRequests)
    }

    @Test
    fun disablingNotificationsCancelsWorkAndExpiresPendingEvents() {
        val store = InMemoryEmaEventStore().apply {
            save(EmaEvent.pending("pending", now.plusSeconds(3_600), zone))
        }
        val gateway = RecordingEmaWorkGateway()
        val coordinator = EmaScheduleCoordinator(store, gateway, EmaSchedulePlanner { 0.5 })

        coordinator.reconcile(now, zone, config.copy(notificationsEnabled = false))

        assertEquals(1, gateway.cancelRequests)
        assertEquals(EmaResponseStatus.EXPIRED, store.get("pending")?.status)
        assertTrue(gateway.prompts.isEmpty())
    }

    @Test
    fun reconfigurationReplacesPendingSchedule() {
        val store = InMemoryEmaEventStore().apply {
            save(EmaEvent.pending("old", now.plusSeconds(3_600), zone))
        }
        val gateway = RecordingEmaWorkGateway()
        var nextId = 0
        val coordinator = EmaScheduleCoordinator(
            store,
            gateway,
            EmaSchedulePlanner { 0.5 },
            EmaIdGenerator { "new-${++nextId}" },
        )

        coordinator.reconfigure(now, zone, config.copy(checkInsPerDay = 4))

        assertEquals(EmaResponseStatus.EXPIRED, store.get("old")?.status)
        assertEquals(8, store.all().count { it.status == EmaResponseStatus.PENDING })
        assertEquals(1, gateway.cancelRequests)
    }

    @Test
    fun reenqueuesARecentlyDuePendingPromptAfterProcessRecovery() {
        val store = InMemoryEmaEventStore().apply {
            save(EmaEvent.pending("due", now.minusSeconds(60), zone))
        }
        val gateway = RecordingEmaWorkGateway()

        EmaScheduleCoordinator(store, gateway, EmaSchedulePlanner { 0.5 })
            .reconcile(now, zone, config)

        assertTrue(gateway.prompts.any { it.eventId == "due" })
    }
}

private class RecordingEmaWorkGateway : EmaWorkGateway {
    val prompts = mutableListOf<EmaScheduledPrompt>()
    var refreshRequests = 0
    var cancelRequests = 0

    override fun enqueuePrompt(prompt: EmaScheduledPrompt) {
        if (prompts.none { it.eventId == prompt.eventId }) prompts += prompt
    }

    override fun ensureScheduleRefresh() {
        refreshRequests++
    }

    override fun cancelAll() {
        cancelRequests++
    }
}
