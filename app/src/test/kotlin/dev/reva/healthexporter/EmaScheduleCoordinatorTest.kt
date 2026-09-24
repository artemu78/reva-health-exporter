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
        assertEquals(2, gateway.refreshRequests)
    }

    @Test
    fun enqueuesPendingDueAndAllFuturePromptsOnReconcile() {
        val store = InMemoryEmaEventStore().apply {
            save(EmaEvent.pending("pending-due", now.minusSeconds(300), zone))
        }
        val gateway = RecordingEmaWorkGateway()
        var nextId = 0
        val coordinator = EmaScheduleCoordinator(
            store = store,
            workGateway = gateway,
            planner = EmaSchedulePlanner { 0.5 },
            idGenerator = EmaIdGenerator { "event-${++nextId}" },
        )

        coordinator.reconcile(now, zone, config)

        assertTrue(gateway.prompts.any { it.eventId == "pending-due" })
        val futurePrompts = gateway.prompts.filter { it.eventId != "pending-due" }
        assertTrue(futurePrompts.isNotEmpty())
        assertTrue(futurePrompts.all { it.scheduledAt.isAfter(now) })
    }

    @Test
    fun expiresSupersededDuePromptsLeavingOnlyLatestDueOnReconcile() {
        val dueTime = now.minusSeconds(300)
        val store = InMemoryEmaEventStore().apply {
            save(EmaEvent.pending("due-a", dueTime, zone))
            save(EmaEvent.pending("due-b", dueTime, zone))
            save(EmaEvent.pending("due-c", dueTime.plusSeconds(60), zone))
        }
        val gateway = RecordingEmaWorkGateway()
        val notifications = CoordinatorRecordingEmaNotificationGateway()
        val promptHandler = EmaPromptHandler(store, notifications)
        val coordinator = EmaScheduleCoordinator(
            store = store,
            workGateway = gateway,
            planner = EmaSchedulePlanner { 0.5 },
            promptHandler = promptHandler,
        )

        coordinator.reconcile(now, zone, config)

        assertEquals(EmaResponseStatus.EXPIRED, store.get("due-a")?.status)
        assertEquals(EmaResponseStatus.EXPIRED, store.get("due-b")?.status)
        assertEquals(EmaResponseStatus.PENDING, store.get("due-c")?.status)
        assertEquals(listOf("due-a", "due-b"), notifications.cancelled)
        assertTrue(gateway.prompts.any { it.eventId == "due-c" })
    }

    @Test
    fun expiresPastDatePendingPromptsOnReconcile() {
        val yesterday = now.atZone(zone).toLocalDate().minusDays(1)
        val store = InMemoryEmaEventStore().apply {
            save(
                EmaEvent.pending(
                    id = "yesterday-prompt",
                    scheduledAt = now.minus(Duration.ofHours(25)),
                    zoneId = zone,
                    scheduleDate = yesterday,
                ),
            )
        }
        val gateway = RecordingEmaWorkGateway()
        val notifications = CoordinatorRecordingEmaNotificationGateway()
        val promptHandler = EmaPromptHandler(store, notifications)
        val coordinator = EmaScheduleCoordinator(
            store = store,
            workGateway = gateway,
            planner = EmaSchedulePlanner { 0.5 },
            promptHandler = promptHandler,
        )

        coordinator.reconcile(now, zone, config)

        assertEquals(EmaResponseStatus.EXPIRED, store.get("yesterday-prompt")?.status)
        assertEquals(listOf("yesterday-prompt"), notifications.cancelled)
    }

    @Test
    fun preservesFutureOvernightPromptWhoseScheduleDateIsYesterday() {
        val yesterday = now.atZone(zone).toLocalDate().minusDays(1)
        val futureScheduledAt = now.plus(Duration.ofHours(2))
        val store = InMemoryEmaEventStore().apply {
            save(
                EmaEvent.pending(
                    id = "overnight-prompt",
                    scheduledAt = futureScheduledAt,
                    zoneId = zone,
                    scheduleDate = yesterday,
                ),
            )
        }
        val gateway = RecordingEmaWorkGateway()
        val coordinator = EmaScheduleCoordinator(store, gateway, EmaSchedulePlanner { 0.5 })

        coordinator.reconcile(now, zone, config)

        assertEquals(EmaResponseStatus.PENDING, store.get("overnight-prompt")?.status)
        assertTrue(gateway.prompts.any { it.eventId == "overnight-prompt" })
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

    @Test
    fun replacesPendingScheduleWhenTimezoneChanges() {
        val oldZone = ZoneId.of("UTC")
        val newZone = ZoneId.of("Europe/Moscow")
        val store = InMemoryEmaEventStore().apply {
            save(EmaEvent.pending("old-utc-prompt", now.plusSeconds(3_600), oldZone))
        }
        val gateway = RecordingEmaWorkGateway()
        var nextId = 0
        val coordinator = EmaScheduleCoordinator(
            store = store,
            workGateway = gateway,
            planner = EmaSchedulePlanner { 0.5 },
            idGenerator = EmaIdGenerator { "new-${++nextId}" },
        )

        coordinator.reconcile(now, newZone, config)

        assertEquals(EmaResponseStatus.EXPIRED, store.get("old-utc-prompt")?.status)
        assertTrue(gateway.cancelRequests >= 1)
        assertTrue(gateway.prompts.none { it.eventId == "old-utc-prompt" })
        assertTrue(gateway.prompts.all { store.get(it.eventId)?.timezone == "Europe/Moscow" })
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

private class CoordinatorRecordingEmaNotificationGateway : EmaNotificationGateway {
    val shown = mutableListOf<String>()
    val cancelled = mutableListOf<String>()

    override fun show(eventId: String) {
        shown += eventId
    }

    override fun cancel(eventId: String) {
        cancelled += eventId
    }
}
