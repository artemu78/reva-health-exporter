package dev.reva.healthexporter

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

data class EmaScheduledPrompt(
    val eventId: String,
    val scheduledAt: Instant,
    val expiresAt: Instant,
)

interface EmaWorkGateway {
    fun enqueuePrompt(prompt: EmaScheduledPrompt)
    fun enqueueExpiry(eventId: String, expiresAt: Instant)
    fun ensureScheduleRefresh()
    fun cancelAll()
}

fun interface EmaIdGenerator {
    fun nextId(): String
}

class EmaScheduleCoordinator(
    private val store: EmaEventStore,
    private val workGateway: EmaWorkGateway,
    private val planner: EmaSchedulePlanner = EmaSchedulePlanner(),
    private val idGenerator: EmaIdGenerator = EmaIdGenerator { UUID.randomUUID().toString() },
) {
    fun reconcile(now: Instant, zoneId: ZoneId, config: EmaConfig) {
        expirePastPrompts(now)
        if (!config.notificationsEnabled) {
            workGateway.cancelAll()
            store.all().filter { it.status == EmaResponseStatus.PENDING }.forEach {
                EmaCheckInService(store).expire(it.id)
            }
            return
        }

        scheduleDays(now, zoneId, config, replace = false)
        enqueuePending(now)
        workGateway.ensureScheduleRefresh()
    }

    fun reconfigure(now: Instant, zoneId: ZoneId, config: EmaConfig) {
        workGateway.cancelAll()
        val service = EmaCheckInService(store)
        store.all().filter { it.status == EmaResponseStatus.PENDING }.forEach { service.expire(it.id) }
        if (!config.notificationsEnabled) return
        scheduleDays(now, zoneId, config, replace = true)
        enqueuePending(now)
        workGateway.ensureScheduleRefresh()
    }

    private fun scheduleDays(now: Instant, zoneId: ZoneId, config: EmaConfig, replace: Boolean) {
        val today = now.atZone(zoneId).toLocalDate()
        listOf(today, today.plusDays(1)).forEach { date ->
            val pendingDifferentZone = store.all().filter {
                it.scheduleDate == date && it.status == EmaResponseStatus.PENDING && it.timezone != zoneId.id
            }
            if (pendingDifferentZone.isNotEmpty()) {
                workGateway.cancelAll()
                val service = EmaCheckInService(store)
                pendingDifferentZone.forEach { service.expire(it.id) }
            }
            val alreadyScheduled = store.all().any {
                it.scheduleDate == date && it.timezone == zoneId.id
            }
            if (!alreadyScheduled || replace) planner.plan(date, zoneId, config)
                .filter { it.isAfter(now) }
                .forEach { scheduledAt ->
                    store.save(
                        EmaEvent.pending(
                            id = idGenerator.nextId(),
                            scheduledAt = scheduledAt,
                            zoneId = zoneId,
                            scheduleDate = date,
                        ),
                    )
                }
        }
    }

    private fun enqueuePending(now: Instant) {
        store.all().filter { event ->
            event.status == EmaResponseStatus.PENDING &&
                event.scheduledAt.plus(RESPONSE_WINDOW).isAfter(now)
        }.forEach { event ->
            workGateway.enqueuePrompt(
                EmaScheduledPrompt(
                    eventId = event.id,
                    scheduledAt = event.scheduledAt,
                    expiresAt = event.scheduledAt.plus(RESPONSE_WINDOW),
                ),
            )
        }
    }

    private fun expirePastPrompts(now: Instant) {
        val service = EmaCheckInService(store)
        store.all().filter {
            it.status == EmaResponseStatus.PENDING && !it.scheduledAt.plus(RESPONSE_WINDOW).isAfter(now)
        }.forEach { service.expire(it.id) }
    }

    companion object {
        val RESPONSE_WINDOW: Duration = Duration.ofHours(2)
    }
}
