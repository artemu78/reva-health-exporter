package dev.reva.healthexporter

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import java.io.File
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun versionTwoPartialFixtureRoundTripsWithoutInventingMissingValues() {
        val expected = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("fixtures/ema_v2/partial.json"),
        ).bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
        val event = deserializeEmaEvent(expected)
        assertEquals(2, event?.schemaVersion)
        assertEquals(4, event?.answers?.mood)
        assertNull(event?.answers?.energy)
        assertNull(event?.activity)
        assertEquals(expected, serializeEmaEvent(checkNotNull(event)))
    }

    @Test
    fun versionTwoSchemaAcceptsPartialAnswersAndRejectsEmptyOrOutOfRangeAnswers() {
        val schemaFile = listOf(File("../docs/ema-event-v2.schema.json"), File("docs/ema-event-v2.schema.json"))
            .first { it.exists() }
        val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(schemaFile.readText())
        val mapper = ObjectMapper()
        val base = """{"schemaVersion":2,"id":"synthetic","scheduleDate":"2026-09-26","scheduledAt":"2026-09-26T09:00:00Z","answeredAt":"2026-09-26T09:01:00Z","status":"answered","timezone":"UTC"}"""
        val partial = base.replace("\"status\"", "\"mood\":3,\"status\"")
        val activityOnly = base.replace("\"status\"", "\"activity\":\"resting\",\"status\"")
        val zero = base.replace("\"status\"", "\"stress\":0,\"status\"")
        assertTrue(schema.validate(mapper.readTree(partial)).isEmpty())
        assertTrue(schema.validate(mapper.readTree(activityOnly)).isEmpty())
        org.junit.Assert.assertFalse(schema.validate(mapper.readTree(base)).isEmpty())
        org.junit.Assert.assertFalse(schema.validate(mapper.readTree(zero)).isEmpty())
    }
}
