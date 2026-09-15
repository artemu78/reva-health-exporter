package dev.reva.healthexporter

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EmaEventStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val scheduledAt = Instant.parse("2026-09-15T09:15:30.123Z")
    private val answeredAt = Instant.parse("2026-09-15T09:17:02.456Z")

    @Test
    fun answeredEventPreservesScheduleAndNumericObservationsAcrossReload() {
        val directory = temporaryFolder.newFolder("ema-events")
        val store = FileEmaEventStore(directory)
        val service = EmaCheckInService(store)
        store.save(EmaEvent.pending("event-1", scheduledAt, ZoneId.of("Europe/Moscow")))

        service.answer(
            eventId = "event-1",
            answeredAt = answeredAt,
            answers = EmaAnswers(mood = 4, energy = 2, focus = 3, stress = 4),
            activity = "work_coding",
            activityLabel = "Work / coding",
            note = null,
        )

        val restored = FileEmaEventStore(directory).get("event-1")
        assertEquals(1, restored?.schemaVersion)
        assertEquals(scheduledAt, restored?.scheduledAt)
        assertEquals(answeredAt, restored?.answeredAt)
        assertEquals(4, restored?.answers?.mood)
        assertEquals(2, restored?.answers?.energy)
        assertEquals(3, restored?.answers?.focus)
        assertEquals(4, restored?.answers?.stress)
        assertEquals("work_coding", restored?.activity)
        assertEquals("Work / coding", restored?.activityLabel)
        assertNull(restored?.note)
        assertEquals(EmaResponseStatus.ANSWERED, restored?.status)
        assertEquals("Europe/Moscow", restored?.timezone)
    }

    @Test
    fun dismissAndExpireOnlyPendingEvents() {
        val store = InMemoryEmaEventStore()
        val service = EmaCheckInService(store)
        store.save(EmaEvent.pending("dismissed", scheduledAt, ZoneId.of("UTC")))
        store.save(EmaEvent.pending("expired", scheduledAt, ZoneId.of("UTC")))

        service.dismiss("dismissed")
        service.expire("expired")
        service.expire("dismissed")

        assertEquals(EmaResponseStatus.DISMISSED, store.get("dismissed")?.status)
        assertEquals(EmaResponseStatus.EXPIRED, store.get("expired")?.status)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsScaleValuesOutsideOneToFive() {
        val store = InMemoryEmaEventStore().apply {
            save(EmaEvent.pending("event-2", scheduledAt, ZoneId.of("UTC")))
        }

        EmaCheckInService(store).answer(
            eventId = "event-2",
            answeredAt = answeredAt,
            answers = EmaAnswers(mood = 0, energy = 2, focus = 3, stress = 4),
            activity = "resting",
            note = null,
        )
    }
}
