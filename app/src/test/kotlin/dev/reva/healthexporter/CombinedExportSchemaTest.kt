package dev.reva.healthexporter

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedExportSchemaTest {

    private val serializer = ExportBatchSerializer()
    private val objectMapper = ObjectMapper()

    private fun getSchemaFile(): File {
        val rootPath = File("../docs/export-envelope-v1.schema.json")
        if (rootPath.exists()) return rootPath
        val directPath = File("docs/export-envelope-v1.schema.json")
        if (directPath.exists()) return directPath
        error("Schema file not found in ${rootPath.absolutePath} or ${directPath.absolutePath}")
    }

    @Test
    fun goldenCombinedExportValidatesAgainstJsonSchema() {
        val schemaFile = getSchemaFile()
        val schemaJson = schemaFile.readText(Charsets.UTF_8)

        val fixtureStream = getFixtureStream("fixtures/combined_export_v1/combined_golden_export.json")
        val fixtureText = fixtureStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

        val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        val schema = factory.getSchema(schemaJson)
        val jsonNode = objectMapper.readTree(fixtureText)
        val validationMessages = schema.validate(jsonNode)

        assertTrue(
            "Fixture failed schema validation: ${validationMessages.joinToString { it.message }}",
            validationMessages.isEmpty(),
        )
    }

    @Test
    fun goldenCombinedExportParsesCorrectlyIntoModel() {
        val fixtureStream = getFixtureStream("fixtures/combined_export_v1/combined_golden_export.json")
        val fixtureText = fixtureStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

        val batch = serializer.parseJson(fixtureText)

        // Health Connect Batch assertions
        assertEquals("00000000-0000-4000-8000-000000000001", batch.header.batchId)
        assertEquals(3, batch.records.size)
        val exercise = batch.records.filterIsInstance<CanonicalExerciseSessionRecord>().first()
        assertEquals("com.xiaomi.wearable", exercise.metadata.origin)
        assertEquals("Outdoor Walk", exercise.title)
        assertEquals(79, exercise.exerciseType)
        assertEquals(1, exercise.segments.size)
        assertEquals(1, exercise.laps.size)

        // EMA Events assertions
        assertEquals(4, batch.emaEvents.size)

        val answered = batch.emaEvents.first { it.id == "018f-example-event-001" }
        assertEquals(EmaResponseStatus.ANSWERED, answered.status)
        assertEquals("exercise_stretching", answered.activity)
        assertEquals("Exercise / stretching", answered.activityLabel)
        assertEquals("Morning routine workout", answered.note)
        assertEquals(4, answered.answers?.mood)
        assertEquals(3, answered.answers?.energy)
        assertEquals(4, answered.answers?.focus)
        assertEquals(2, answered.answers?.stress)
        assertEquals(4, answered.answers?.additional?.get("clarity"))

        val pending = batch.emaEvents.first { it.id == "018f-example-event-002" }
        assertEquals(EmaResponseStatus.PENDING, pending.status)
        assertNull(pending.answers)
        assertNull(pending.answeredAt)

        val dismissed = batch.emaEvents.first { it.id == "018f-example-event-003" }
        assertEquals(EmaResponseStatus.DISMISSED, dismissed.status)
        assertNull(dismissed.answers)

        val expired = batch.emaEvents.first { it.id == "018f-example-event-004" }
        assertEquals(EmaResponseStatus.EXPIRED, expired.status)
        assertNull(expired.answers)
    }

    @Test
    fun reSerializedBatchValidatesAgainstJsonSchemaAndRoundTrips() {
        val fixtureStream = getFixtureStream("fixtures/combined_export_v1/combined_golden_export.json")
        val fixtureText = fixtureStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

        val batch = serializer.parseJson(fixtureText)
        val reSerialized = serializer.serializeToJson(batch)

        val schemaFile = getSchemaFile()
        val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        val schema = factory.getSchema(schemaFile.readText(Charsets.UTF_8))
        val jsonNode = objectMapper.readTree(reSerialized)
        val validationMessages = schema.validate(jsonNode)

        assertTrue(
            "Re-serialized JSON failed schema validation: ${validationMessages.joinToString { it.message }}",
            validationMessages.isEmpty(),
        )

        val reParsed = serializer.parseJson(reSerialized)
        assertEquals(batch.header.batchId, reParsed.header.batchId)
        assertEquals(batch.records.size, reParsed.records.size)
        assertEquals(batch.emaEvents.size, reParsed.emaEvents.size)
        assertEquals(batch, reParsed)
    }

    @Test
    fun upsertReplacesEarlierEventStateById() {
        val initialPending = EmaEvent(
            schemaVersion = 1,
            id = "event-upsert-1",
            scheduleDate = LocalDate.parse("2026-09-24"),
            scheduledAt = Instant.parse("2026-09-24T06:00:00Z"),
            answeredAt = null,
            answers = null,
            activity = null,
            activityLabel = null,
            note = null,
            status = EmaResponseStatus.PENDING,
            timezone = "Europe/Moscow",
        )

        val updatedAnswered = initialPending.copy(
            status = EmaResponseStatus.ANSWERED,
            answeredAt = Instant.parse("2026-09-24T06:02:00Z"),
            answers = EmaAnswers(mood = 5, energy = 4, focus = 5, stress = 1),
            activity = "meditation",
            activityLabel = "Meditation",
        )

        // Backend ingestion simulation: map by ID
        val backendStore = mutableMapOf<String, EmaEvent>()
        backendStore[initialPending.id] = initialPending
        assertEquals(EmaResponseStatus.PENDING, backendStore["event-upsert-1"]?.status)

        // Upsert on receiving next batch with same ID
        backendStore[updatedAnswered.id] = updatedAnswered
        assertEquals(1, backendStore.size)
        assertEquals(EmaResponseStatus.ANSWERED, backendStore["event-upsert-1"]?.status)
        assertEquals(5, backendStore["event-upsert-1"]?.answers?.mood)
    }

    @Test
    fun combinedEnvelopeRoundTripsPartialVersionTwoEvent() {
        val event = EmaEvent(
            schemaVersion = 2,
            id = "partial-export",
            scheduleDate = LocalDate.parse("2026-09-26"),
            scheduledAt = Instant.parse("2026-09-26T09:00:00Z"),
            answeredAt = Instant.parse("2026-09-26T09:01:00Z"),
            answers = EmaAnswers(stress = 2),
            activity = null,
            activityLabel = null,
            note = null,
            status = EmaResponseStatus.ANSWERED,
            timezone = "UTC",
        )
        val batch = ExportBatch(
            header = BatchHeader(
                installationId = "synthetic-installation",
                batchId = "synthetic-batch",
                createdAt = Instant.parse("2026-09-26T10:00:00Z"),
                timeWindow = TimeWindow(Instant.parse("2026-09-26T00:00:00Z"), Instant.parse("2026-09-27T00:00:00Z")),
                recordCount = 0,
                recordTypes = emptyList(),
            ),
            records = emptyList(),
            emaEvents = listOf(event),
        )
        val serialized = serializer.serializeToJson(batch)
        val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(getSchemaFile().readText())
        assertTrue(schema.validate(objectMapper.readTree(serialized)).isEmpty())
        assertEquals(event, serializer.parseJson(serialized).emaEvents.single())
        org.junit.Assert.assertFalse(serialized.contains("\"mood\":0"))
    }

    @Test
    fun rejectsMissingOrInvalidExportSchemaVersion() {
        val missingVersion = """
            {
              "exportId": "00000000-0000-4000-8000-000000000001",
              "createdAt": "2026-09-24T22:00:00Z",
              "healthConnectBatch": { "schemaVersion": 1, "records": [] }
            }
        """.trimIndent()
        org.junit.Assert.assertThrows(InvalidExportSchemaException::class.java) {
            serializer.parseJson(missingVersion)
        }

        val nonIntVersion = """
            {
              "exportSchemaVersion": "one",
              "exportId": "00000000-0000-4000-8000-000000000001",
              "createdAt": "2026-09-24T22:00:00Z",
              "healthConnectBatch": { "schemaVersion": 1, "records": [] }
            }
        """.trimIndent()
        org.junit.Assert.assertThrows(InvalidExportSchemaException::class.java) {
            serializer.parseJson(nonIntVersion)
        }
    }

    @Test
    fun rejectsMissingOrInvalidHealthConnectBatchSchemaVersion() {
        val missingHcVersion = """
            {
              "exportSchemaVersion": 1,
              "exportId": "00000000-0000-4000-8000-000000000001",
              "createdAt": "2026-09-24T22:00:00Z",
              "healthConnectBatch": { "records": [] }
            }
        """.trimIndent()
        org.junit.Assert.assertThrows(InvalidExportSchemaException::class.java) {
            serializer.parseJson(missingHcVersion)
        }

        val nonIntHcVersion = """
            {
              "exportSchemaVersion": 1,
              "exportId": "00000000-0000-4000-8000-000000000001",
              "createdAt": "2026-09-24T22:00:00Z",
              "healthConnectBatch": { "schemaVersion": "1", "records": [] }
            }
        """.trimIndent()
        org.junit.Assert.assertThrows(InvalidExportSchemaException::class.java) {
            serializer.parseJson(nonIntHcVersion)
        }
    }

    private fun getFixtureStream(path: String): InputStream {
        val stream = javaClass.classLoader?.getResourceAsStream(path)
        assertNotNull("Fixture not found on classpath: $path", stream)
        return stream!!
    }
}
