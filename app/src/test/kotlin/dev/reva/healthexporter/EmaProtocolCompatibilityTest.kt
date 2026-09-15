package dev.reva.healthexporter

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class EmaProtocolCompatibilityTest {
    @Test
    fun frozenAnsweredEventMatchesVersionOneWireFormat() {
        val expected = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("fixtures/ema_v1/answered.json"),
        ).bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
        val event = EmaEvent(
            schemaVersion = 1,
            id = "018f-example-event-001",
            scheduleDate = LocalDate.parse("2026-09-15"),
            scheduledAt = Instant.parse("2026-09-15T09:15:30.123Z"),
            answeredAt = Instant.parse("2026-09-15T09:17:02.456Z"),
            answers = EmaAnswers(
                mood = 4,
                energy = 2,
                focus = 3,
                stress = 4,
                additional = mapOf("calmness" to 5),
            ),
            activity = "work_coding",
            activityLabel = "Work / coding",
            note = "Finishing a focused task",
            status = EmaResponseStatus.ANSWERED,
            timezone = "Europe/Moscow",
        )

        assertEquals(expected, serializeEmaEvent(event))
        assertEquals(event, deserializeEmaEvent(expected))
        assertNotNull(deserializeEmaEvent(expected))
    }
}
