package dev.reva.healthexporter

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
        assertEquals(2, restored?.schemaVersion)
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

    @Test
    fun partialAnswersAndActivityOnlyAnswersRoundTripWithoutZeroes() {
        val directory = temporaryFolder.newFolder("partial-ema-events")
        val store = FileEmaEventStore(directory)
        val service = EmaCheckInService(store)
        store.save(EmaEvent.pending("one-scale", scheduledAt, ZoneId.of("UTC")))
        store.save(EmaEvent.pending("activity-only", scheduledAt, ZoneId.of("UTC")))

        service.answer("one-scale", answeredAt, EmaAnswers(mood = 4), null, null)
        service.answer("activity-only", answeredAt, null, "resting", null, "Resting")

        val scale = FileEmaEventStore(directory).get("one-scale")!!
        assertEquals(2, scale.schemaVersion)
        assertEquals(4, scale.answers?.mood)
        assertNull(scale.answers?.energy)
        assertNull(scale.activity)
        org.junit.Assert.assertFalse(serializeEmaEvent(scale).contains(Regex("\\\"(energy|focus|stress)\\\"\\s*:\\s*0")))

        val activity = FileEmaEventStore(directory).get("activity-only")!!
        assertNull(activity.answers)
        assertEquals("resting", activity.activity)
    }

    @Test
    fun everyPresentScaleRejectsValuesOutsideOneToFive() {
        listOf(
            { EmaAnswers(mood = 0) },
            { EmaAnswers(energy = 6) },
            { EmaAnswers(focus = -1) },
            { EmaAnswers(stress = 9) },
        ).forEach { factory -> assertThrows(IllegalArgumentException::class.java) { factory() } }
    }

    @Test
    fun answeredEventRequiresAtLeastOneMeaningfulAnswer() {
        val store = InMemoryEmaEventStore().apply {
            save(EmaEvent.pending("empty", scheduledAt, ZoneId.of("UTC")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EmaCheckInService(store).answer("empty", answeredAt, EmaAnswers(), null, "note only")
        }
    }

    @Test
    fun versionTwoDeserializerAcceptsPartialAnswersAndRejectsEmptyAnsweredEvent() {
        val partial = """{"schemaVersion":2,"id":"partial","scheduleDate":"2026-09-15","scheduledAt":"2026-09-15T09:15:30.123Z","answeredAt":"2026-09-15T09:17:02.456Z","focus":5,"status":"answered","timezone":"UTC"}"""
        val empty = """{"schemaVersion":2,"id":"empty","scheduleDate":"2026-09-15","scheduledAt":"2026-09-15T09:15:30.123Z","answeredAt":"2026-09-15T09:17:02.456Z","status":"answered","timezone":"UTC"}"""
        assertEquals(5, deserializeEmaEvent(partial)?.answers?.focus)
        assertNull(deserializeEmaEvent(empty))
    }

    @Test(expected = IllegalArgumentException::class)
    fun fileStoreRejectsRegularFileAsDirectory() {
        val file = temporaryFolder.newFile("not-a-directory")
        FileEmaEventStore(file)
    }

    @Test
    fun deserializeRejectsUnsupportedSchemaVersion() {
        val json = """{"schemaVersion":99,"id":"e1","scheduleDate":"2026-09-15","scheduledAt":"2026-09-15T09:15:30.123Z","status":"pending","timezone":"UTC"}"""
        assertNull(deserializeEmaEvent(json))
    }

    @Test
    fun deserializeRejectsIncompleteAnsweredRecord() {
        // Missing activity
        val missingActivity = """{"schemaVersion":1,"id":"e2","scheduleDate":"2026-09-15","scheduledAt":"2026-09-15T09:15:30.123Z","answeredAt":"2026-09-15T09:17:02.456Z","mood":4,"energy":2,"focus":3,"stress":4,"status":"answered","timezone":"UTC"}"""
        assertNull(deserializeEmaEvent(missingActivity))

        // Missing mood
        val missingMood = """{"schemaVersion":1,"id":"e3","scheduleDate":"2026-09-15","scheduledAt":"2026-09-15T09:15:30.123Z","answeredAt":"2026-09-15T09:17:02.456Z","energy":2,"focus":3,"stress":4,"activity":"coding","status":"answered","timezone":"UTC"}"""
        assertNull(deserializeEmaEvent(missingMood))

        // Missing answeredAt
        val missingAnsweredAt = """{"schemaVersion":1,"id":"e4","scheduleDate":"2026-09-15","scheduledAt":"2026-09-15T09:15:30.123Z","mood":4,"energy":2,"focus":3,"stress":4,"activity":"coding","status":"answered","timezone":"UTC"}"""
        assertNull(deserializeEmaEvent(missingAnsweredAt))
    }

    @Test
    fun deserializeRejectsPendingRecordWithAnswers() {
        val pendingWithAnswers = """{"schemaVersion":1,"id":"e5","scheduleDate":"2026-09-15","scheduledAt":"2026-09-15T09:15:30.123Z","mood":4,"energy":2,"focus":3,"stress":4,"status":"pending","timezone":"UTC"}"""
        assertNull(deserializeEmaEvent(pendingWithAnswers))
    }
}
