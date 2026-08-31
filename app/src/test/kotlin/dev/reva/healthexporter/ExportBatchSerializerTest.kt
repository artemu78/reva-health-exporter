package dev.reva.healthexporter

import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportBatchSerializerTest {
    private val serializer = ExportBatchSerializer()

    private val sampleDevice = DeviceMetadata(
        manufacturer = "Xiaomi",
        model = "Smart Band 9",
        type = Device.TYPE_FITNESS_BAND,
    )
    private val sampleMetadata = RecordMetadata(
        recordId = "rec-001",
        origin = "com.mi.health",
        clientRecordId = "client-rec-001",
        clientRecordVersion = 3L,
        recordingMethod = Metadata.RECORDING_METHOD_ACTIVELY_RECORDED,
        device = sampleDevice,
        lastModifiedTime = Instant.parse("2026-08-30T10:15:30Z"),
    )

    private val fullBatch: ExportBatch by lazy {
        val window = TimeWindow(
            startInclusive = Instant.parse("2026-08-29T00:00:00Z"),
            endExclusive = Instant.parse("2026-08-30T00:00:00Z"),
        )
        val records = listOf(
            CanonicalStepsRecord(
                startTime = Instant.parse("2026-08-29T08:00:00Z"),
                startZoneOffset = ZoneOffset.ofHours(3),
                endTime = Instant.parse("2026-08-29T08:15:00Z"),
                endZoneOffset = ZoneOffset.ofHours(3),
                metadata = sampleMetadata.copy(recordId = "rec-steps-1"),
                count = 1500,
            ),
            CanonicalHeartRateRecord(
                startTime = Instant.parse("2026-08-29T08:00:00Z"),
                startZoneOffset = ZoneOffset.ofHours(3),
                endTime = Instant.parse("2026-08-29T08:05:00Z"),
                endZoneOffset = ZoneOffset.ofHours(3),
                metadata = sampleMetadata.copy(recordId = "rec-hr-1"),
                samples = listOf(
                    HeartRateSample(Instant.parse("2026-08-29T08:01:00Z"), 72),
                    HeartRateSample(Instant.parse("2026-08-29T08:03:00Z"), 76),
                ),
            ),
            CanonicalDistanceRecord(
                startTime = Instant.parse("2026-08-29T08:00:00Z"),
                startZoneOffset = ZoneOffset.ofHours(3),
                endTime = Instant.parse("2026-08-29T08:15:00Z"),
                endZoneOffset = ZoneOffset.ofHours(3),
                metadata = sampleMetadata.copy(recordId = "rec-dist-1"),
                distanceMeters = 1250.5,
            ),
            CanonicalTotalCaloriesBurnedRecord(
                startTime = Instant.parse("2026-08-29T08:00:00Z"),
                startZoneOffset = ZoneOffset.ofHours(3),
                endTime = Instant.parse("2026-08-29T08:15:00Z"),
                endZoneOffset = ZoneOffset.ofHours(3),
                metadata = sampleMetadata.copy(recordId = "rec-cal-1"),
                energyKilocalories = 65.4,
            ),
            CanonicalSleepSessionRecord(
                startTime = Instant.parse("2026-08-29T01:00:00Z"),
                startZoneOffset = ZoneOffset.ofHours(3),
                endTime = Instant.parse("2026-08-29T07:30:00Z"),
                endZoneOffset = ZoneOffset.ofHours(3),
                metadata = sampleMetadata.copy(recordId = "rec-sleep-1"),
                title = "Night Sleep",
                notes = "Good quality",
                stages = listOf(
                    SleepStage(
                        startTime = Instant.parse("2026-08-29T01:00:00Z"),
                        endTime = Instant.parse("2026-08-29T02:00:00Z"),
                        stage = SleepSessionRecord.STAGE_TYPE_LIGHT,
                    ),
                    SleepStage(
                        startTime = Instant.parse("2026-08-29T02:00:00Z"),
                        endTime = Instant.parse("2026-08-29T04:00:00Z"),
                        stage = SleepSessionRecord.STAGE_TYPE_DEEP,
                    ),
                ),
            ),
            CanonicalExerciseSessionRecord(
                startTime = Instant.parse("2026-08-29T18:00:00Z"),
                startZoneOffset = ZoneOffset.ofHours(3),
                endTime = Instant.parse("2026-08-29T18:45:00Z"),
                endZoneOffset = ZoneOffset.ofHours(3),
                metadata = sampleMetadata.copy(recordId = "rec-ex-1"),
                exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
                title = "Evening Walk",
                notes = "Park loop",
                segments = listOf(
                    ExerciseSegmentModel(
                        startTime = Instant.parse("2026-08-29T18:00:00Z"),
                        endTime = Instant.parse("2026-08-29T18:30:00Z"),
                        segmentType = ExerciseSegment.EXERCISE_SEGMENT_TYPE_WALKING,
                        repetitions = 0,
                    ),
                ),
                laps = listOf(
                    ExerciseLapModel(
                        startTime = Instant.parse("2026-08-29T18:00:00Z"),
                        endTime = Instant.parse("2026-08-29T18:20:00Z"),
                        lengthMeters = 1500.0,
                    ),
                ),
            ),
        )
        ExportBatch(
            header = BatchHeader(
                schemaVersion = 1,
                installationId = "inst-pseudo-uuid-1",
                batchId = "batch-uuid-001",
                createdAt = Instant.parse("2026-08-30T01:00:00Z"),
                timeWindow = window,
                recordCount = records.size,
                recordTypes = records.map { it.recordType }.distinct().sorted(),
            ),
            records = records,
        )
    }

    @Test
    fun serializesAndParsesBatchWithAllConfirmedTypes() {
        val ndjson = serializer.serializeToNdjson(fullBatch)

        val lines = ndjson.trimEnd().lines()
        assertEquals(7, lines.size) // 1 header + 6 records

        val parsed = serializer.parseNdjson(ndjson)
        assertEquals(fullBatch.header, parsed.header)
        assertEquals(fullBatch.records.size, parsed.records.size)

        // Compare each record in parsed batch
        fullBatch.records.forEach { expected ->
            val actual = parsed.records.firstOrNull { it.recordType == expected.recordType && it.startTime == expected.startTime }
            assertNotNull("Missing record ${expected.recordType}", actual)
            assertEquals(expected.recordType, actual!!.recordType)
            assertEquals(expected.metadata.origin, actual.metadata.origin)
            assertEquals(expected.startTime, actual.startTime)
            assertEquals(expected.endTime, actual.endTime)
            org.junit.Assert.assertNull(actual.metadata.recordId)
            org.junit.Assert.assertNull(actual.metadata.device)
            org.junit.Assert.assertNull(actual.metadata.clientRecordId)
        }
    }

    @Test
    fun serializesAndParsesEmptyBatch() {
        val emptyBatch = ExportBatch(
            header = BatchHeader(
                schemaVersion = 1,
                installationId = "inst-empty",
                batchId = "batch-empty",
                createdAt = Instant.parse("2026-08-30T12:00:00Z"),
                timeWindow = TimeWindow(
                    startInclusive = Instant.parse("2026-08-29T00:00:00Z"),
                    endExclusive = Instant.parse("2026-08-30T00:00:00Z"),
                ),
                recordCount = 0,
                recordTypes = emptyList(),
            ),
            records = emptyList(),
        )

        val ndjson = serializer.serializeToNdjson(emptyBatch)
        val lines = ndjson.trimEnd().lines()
        assertEquals(1, lines.size)

        val parsed = serializer.parseNdjson(ndjson)
        assertEquals(emptyBatch, parsed)
    }

    @Test
    fun serializationIsDeterministic() {
        val ndjson1 = serializer.serializeToNdjson(fullBatch)
        val ndjson2 = serializer.serializeToNdjson(fullBatch)
        assertEquals(ndjson1, ndjson2)

        // Out of order records in input batch should be serialized in stable sorted order
        val reversedBatch = fullBatch.copy(records = fullBatch.records.reversed())
        val ndjsonReversed = serializer.serializeToNdjson(reversedBatch)
        assertEquals(ndjson1, ndjsonReversed)
    }

    @Test
    fun gzipCompressionAndDecompressionRoundTrip() {
        val gzipBytes = serializer.serializeToGzipBytes(fullBatch)
        assertTrue(gzipBytes.isNotEmpty())

        // 1. Independent standard decompress
        val decompressedText = GZIPInputStream(ByteArrayInputStream(gzipBytes))
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        assertEquals(serializer.serializeToNdjson(fullBatch), decompressedText)

        // 2. Serializer helper decompress
        val parsedFromGzip = serializer.decompressAndParse(gzipBytes)
        assertEquals(fullBatch.header, parsedFromGzip.header)
        assertEquals(fullBatch.records.size, parsedFromGzip.records.size)
        fullBatch.records.forEach { expected ->
            val actual = parsedFromGzip.records.first { it.recordType == expected.recordType && it.startTime == expected.startTime }
            assertEquals(expected.recordType, actual.recordType)
            assertEquals(expected.metadata.origin, actual.metadata.origin)
            org.junit.Assert.assertNull(actual.metadata.recordId)
        }
    }

    @Test
    fun rejectsEmptyOrBlankNdjson() {
        assertThrows(InvalidExportSchemaException::class.java) {
            serializer.parseNdjson("")
        }
        assertThrows(InvalidExportSchemaException::class.java) {
            serializer.parseNdjson("   \n\n  ")
        }
    }

    @Test
    fun rejectsMissingHeaderOrCorruptedHeader() {
        val recordOnly = """{"recordType":"steps","recordId":"s1","origin":"com.mi.health","startTime":"2026-08-30T10:00:00Z","endTime":"2026-08-30T10:15:00Z","count":100}"""
        assertThrows(InvalidExportSchemaException::class.java) {
            serializer.parseNdjson(recordOnly)
        }

        val invalidJsonHeader = """{not-valid-json}"""
        assertThrows(InvalidExportSchemaException::class.java) {
            serializer.parseNdjson(invalidJsonHeader)
        }

        val wrongVersionHeader = """{"recordType":"header","schemaVersion":99,"installationId":"i","batchId":"b","createdAt":"2026-08-30T00:00:00Z","timeWindow":{"startInclusive":"2026-08-29T00:00:00Z","endExclusive":"2026-08-30T00:00:00Z"},"recordCount":0,"recordTypes":[]}"""
        assertThrows(InvalidExportSchemaException::class.java) {
            serializer.parseNdjson(wrongVersionHeader)
        }
    }

    @Test
    fun rejectsMalformedRecordLineOrInvalidFields() {
        val validHeader = """{"recordType":"header","schemaVersion":1,"installationId":"i","batchId":"b","createdAt":"2026-08-30T00:00:00Z","timeWindow":{"startInclusive":"2026-08-29T00:00:00Z","endExclusive":"2026-08-30T00:00:00Z"},"recordCount":1,"recordTypes":["steps"]}"""

        // Line 2 is bad JSON
        assertThrows(InvalidExportSchemaException::class.java) {
            serializer.parseNdjson("$validHeader\n{broken json")
        }

        // Line 2 has unknown recordType
        val unknownType = """{"recordType":"unknown_type","recordId":"s1","origin":"com.mi.health","startTime":"2026-08-30T10:00:00Z","endTime":"2026-08-30T10:15:00Z"}"""
        assertThrows(InvalidExportSchemaException::class.java) {
            serializer.parseNdjson("$validHeader\n$unknownType")
        }

        // Line 2 has invalid timestamp
        val invalidTime = """{"recordType":"steps","recordId":"s1","origin":"com.mi.health","startTime":"invalid-date","endTime":"2026-08-30T10:15:00Z","count":100}"""
        assertThrows(InvalidExportSchemaException::class.java) {
            serializer.parseNdjson("$validHeader\n$invalidTime")
        }

        // Line 2 has invalid type for count (string instead of long)
        val invalidCount = """{"recordType":"steps","recordId":"s1","origin":"com.mi.health","startTime":"2026-08-30T10:00:00Z","endTime":"2026-08-30T10:15:00Z","count":"many"}"""
        assertThrows(InvalidExportSchemaException::class.java) {
            serializer.parseNdjson("$validHeader\n$invalidCount")
        }
    }

    @Test
    fun rejectsFractionalNumbersInIntegralFields() {
        val validHeader = """{"recordType":"header","schemaVersion":1,"installationId":"i","batchId":"b","createdAt":"2026-08-30T00:00:00Z","timeWindow":{"startInclusive":"2026-08-29T00:00:00Z","endExclusive":"2026-08-30T00:00:00Z"},"recordCount":1,"recordTypes":["steps"]}"""

        // Fractional count
        val fracCount = """{"recordType":"steps","recordId":"s1","origin":"com.mi.health","startTime":"2026-08-30T10:00:00Z","endTime":"2026-08-30T10:15:00Z","count":100.5}"""
        assertThrows(InvalidExportSchemaException::class.java) {
            serializer.parseNdjson("$validHeader\n$fracCount")
        }

        // Fractional schemaVersion in header
        val fracVersionHeader = """{"recordType":"header","schemaVersion":1.5,"installationId":"i","batchId":"b","createdAt":"2026-08-30T00:00:00Z","timeWindow":{"startInclusive":"2026-08-29T00:00:00Z","endExclusive":"2026-08-30T00:00:00Z"},"recordCount":0,"recordTypes":[]}"""
        assertThrows(InvalidExportSchemaException::class.java) {
            serializer.parseNdjson(fracVersionHeader)
        }

        // Fractional exerciseType
        val validExHeader = """{"recordType":"header","schemaVersion":1,"installationId":"i","batchId":"b","createdAt":"2026-08-30T00:00:00Z","timeWindow":{"startInclusive":"2026-08-29T00:00:00Z","endExclusive":"2026-08-30T00:00:00Z"},"recordCount":1,"recordTypes":["exercise_session"]}"""
        val fracExerciseType = """{"recordType":"exercise_session","recordId":"e1","origin":"com.mi.health","startTime":"2026-08-30T10:00:00Z","endTime":"2026-08-30T10:15:00Z","exerciseType":79.9}"""
        assertThrows(InvalidExportSchemaException::class.java) {
            serializer.parseNdjson("$validExHeader\n$fracExerciseType")
        }
    }

    @Test
    fun rejectsIncompatibleTypesInOptionalFields() {
        val validHeader = """{"recordType":"header","schemaVersion":1,"installationId":"i","batchId":"b","createdAt":"2026-08-30T00:00:00Z","timeWindow":{"startInclusive":"2026-08-29T00:00:00Z","endExclusive":"2026-08-30T00:00:00Z"},"recordCount":1,"recordTypes":["steps"]}"""

        // Numeric startZoneOffset
        val numericOffset = """{"recordType":"steps","recordId":"s1","origin":"com.mi.health","startTime":"2026-08-30T10:00:00Z","startZoneOffset":7,"endTime":"2026-08-30T10:15:00Z","count":100}"""
        assertThrows(InvalidExportSchemaException::class.java) {
            serializer.parseNdjson("$validHeader\n$numericOffset")
        }

        // String clientRecordVersion
        val stringVersion = """{"recordType":"steps","recordId":"s1","origin":"com.mi.health","startTime":"2026-08-30T10:00:00Z","clientRecordVersion":"v1","endTime":"2026-08-30T10:15:00Z","count":100}"""
        assertThrows(InvalidExportSchemaException::class.java) {
            serializer.parseNdjson("$validHeader\n$stringVersion")
        }

        // String device (should be object)
        val stringDevice = """{"recordType":"steps","recordId":"s1","origin":"com.mi.health","startTime":"2026-08-30T10:00:00Z","device":"watch","endTime":"2026-08-30T10:15:00Z","count":100}"""
        assertThrows(InvalidExportSchemaException::class.java) {
            serializer.parseNdjson("$validHeader\n$stringDevice")
        }
    }

    @Test
    fun parsesExplicitJsonNullOptionalFieldsAsNull() {
        val validHeader = """{"recordType":"header","schemaVersion":1,"installationId":"i","batchId":"b","createdAt":"2026-08-30T00:00:00Z","timeWindow":{"startInclusive":"2026-08-29T00:00:00Z","endExclusive":"2026-08-30T00:00:00Z"},"recordCount":1,"recordTypes":["steps"]}"""
        val recordWithNulls = """{"recordType":"steps","recordId":"s1","origin":"com.mi.health","startTime":"2026-08-30T10:00:00Z","startZoneOffset":null,"endTime":"2026-08-30T10:15:00Z","endZoneOffset":null,"clientRecordId":null,"clientRecordVersion":null,"recordingMethod":null,"device":null,"lastModifiedTime":null,"count":100}"""

        val batch = serializer.parseNdjson("$validHeader\n$recordWithNulls")
        val record = batch.records.first() as CanonicalStepsRecord

        assertEquals(100L, record.count)
        org.junit.Assert.assertNull(record.startZoneOffset)
        org.junit.Assert.assertNull(record.endZoneOffset)
        org.junit.Assert.assertNull(record.metadata.clientRecordId)
        org.junit.Assert.assertNull(record.metadata.clientRecordVersion)
        org.junit.Assert.assertNull(record.metadata.recordingMethod)
        org.junit.Assert.assertNull(record.metadata.device)
        org.junit.Assert.assertNull(record.metadata.lastModifiedTime)
    }

    @Test
    fun serializesAndParsesJsonBatchWithAllConfirmedTypes() {
        val json = serializer.serializeToJson(fullBatch)
        assertTrue(json.isNotBlank())

        val parsed = serializer.parseJson(json)
        assertEquals(fullBatch.header, parsed.header)
        assertEquals(fullBatch.records.size, parsed.records.size)

        // Compare each record in parsed batch
        fullBatch.records.forEach { expected ->
            val actual = parsed.records.firstOrNull { it.recordType == expected.recordType && it.startTime == expected.startTime }
            assertNotNull("Missing record ${expected.recordType}", actual)
            assertEquals(expected.recordType, actual!!.recordType)
            assertEquals(expected.metadata.origin, actual.metadata.origin)
            assertEquals(expected.startTime, actual.startTime)
            assertEquals(expected.startZoneOffset, actual.startZoneOffset)
            assertEquals(expected.endTime, actual.endTime)
            assertEquals(expected.endZoneOffset, actual.endZoneOffset)
            org.junit.Assert.assertNull(actual.metadata.recordId)
            org.junit.Assert.assertNull(actual.metadata.device)
            org.junit.Assert.assertNull(actual.metadata.clientRecordId)
            org.junit.Assert.assertNull(actual.metadata.recordingMethod)
        }
    }

    @Test
    fun serializesAndParsesEmptyJsonBatch() {
        val emptyBatch = ExportBatch(
            header = BatchHeader(
                schemaVersion = 1,
                installationId = "inst-empty",
                batchId = "batch-empty",
                createdAt = Instant.parse("2026-08-30T12:00:00Z"),
                timeWindow = TimeWindow(
                    startInclusive = Instant.parse("2026-08-29T00:00:00Z"),
                    endExclusive = Instant.parse("2026-08-30T00:00:00Z"),
                ),
                recordCount = 0,
                recordTypes = emptyList(),
            ),
            records = emptyList(),
        )

        val json = serializer.serializeToJson(emptyBatch)
        val parsed = serializer.parseJson(json)
        assertEquals(emptyBatch, parsed)
    }

    @Test
    fun jsonSerializationIsDeterministic() {
        val json1 = serializer.serializeToJson(fullBatch)
        val json2 = serializer.serializeToJson(fullBatch)
        assertEquals(json1, json2)

        // Out of order records in input batch should be serialized in stable sorted order
        val reversedBatch = fullBatch.copy(records = fullBatch.records.reversed())
        val jsonReversed = serializer.serializeToJson(reversedBatch)
        assertEquals(json1, jsonReversed)
    }

    @Test
    fun strippedFieldsAreNeverSerializedIntoOutputJson() {
        val json = serializer.serializeToJson(fullBatch)

        // Metadata and provenance fields that must be stripped from JSON records
        assertFalse("Output JSON must not contain 'device' key", json.contains("\"device\""))
        assertFalse("Output JSON must not contain 'recordId' key", json.contains("\"recordId\""))
        assertFalse("Output JSON must not contain 'clientRecordId' key", json.contains("\"clientRecordId\""))
        assertFalse("Output JSON must not contain 'recordingMethod' key", json.contains("\"recordingMethod\""))
        assertFalse("Output JSON must not contain 'clientRecordVersion' key", json.contains("\"clientRecordVersion\""))
        assertFalse("Output JSON must not contain 'lastModifiedTime' key", json.contains("\"lastModifiedTime\""))
        assertFalse("Output JSON must not contain 'manufacturer'", json.contains("Xiaomi"))
        assertFalse("Output JSON must not contain 'model'", json.contains("Smart Band 9"))

        // Essential header and timing/origin fields must be preserved
        assertTrue("Output JSON must contain 'schemaVersion'", json.contains("\"schemaVersion\":1"))
        assertTrue("Output JSON must contain 'installationId'", json.contains("\"installationId\":\"inst-pseudo-uuid-1\""))
        assertTrue("Output JSON must contain 'batchId'", json.contains("\"batchId\":\"batch-uuid-001\""))
        assertTrue("Output JSON must contain 'origin'", json.contains("\"origin\":\"com.mi.health\""))
        assertTrue("Output JSON must contain 'startTime'", json.contains("\"startTime\":\"2026-08-29T08:00:00Z\""))
        assertTrue("Output JSON must contain 'count'", json.contains("\"count\":1500"))
    }

    @Test
    fun rejectsEmptyOrBlankJson() {
        assertThrows(InvalidExportSchemaException::class.java) {
            serializer.parseJson("")
        }
        assertThrows(InvalidExportSchemaException::class.java) {
            serializer.parseJson("   \n\n  ")
        }
    }

    @Test
    fun rejectsMissingHeaderOrRecordsInJson() {
        assertThrows(InvalidExportSchemaException::class.java) {
            serializer.parseJson("""{"records":[]}""")
        }
        assertThrows(InvalidExportSchemaException::class.java) {
            serializer.parseJson("""{"header":{}}""")
        }
        assertThrows(InvalidExportSchemaException::class.java) {
            serializer.parseJson("""{invalid-json}""")
        }
    }

    @Test
    fun rejectsRecordCountMismatchInJson() {
        val json = """
            {
              "header": {
                "schemaVersion": 1,
                "installationId": "i",
                "batchId": "b",
                "createdAt": "2026-08-30T00:00:00Z",
                "timeWindow": {
                  "startInclusive": "2026-08-29T00:00:00Z",
                  "endExclusive": "2026-08-30T00:00:00Z"
                },
                "recordCount": 2,
                "recordTypes": ["steps"]
              },
              "records": [
                {
                  "recordType": "steps",
                  "origin": "com.mi.health",
                  "startTime": "2026-08-29T08:00:00Z",
                  "endTime": "2026-08-29T08:15:00Z",
                  "count": 100
                }
              ]
            }
        """.trimIndent()
        assertThrows(InvalidExportSchemaException::class.java) {
            serializer.parseJson(json)
        }
    }

    @Test
    fun jsonSerializationDeterministicForSameTimeRecordsWithNullIds() {
        val window = TimeWindow(
            startInclusive = Instant.parse("2026-08-29T00:00:00Z"),
            endExclusive = Instant.parse("2026-08-30T00:00:00Z"),
        )
        val rec1 = CanonicalStepsRecord(
            startTime = Instant.parse("2026-08-29T08:00:00Z"),
            startZoneOffset = ZoneOffset.UTC,
            endTime = Instant.parse("2026-08-29T08:15:00Z"),
            endZoneOffset = ZoneOffset.UTC,
            metadata = RecordMetadata(origin = "com.mi.health"),
            count = 100,
        )
        val rec2 = CanonicalStepsRecord(
            startTime = Instant.parse("2026-08-29T08:00:00Z"),
            startZoneOffset = ZoneOffset.UTC,
            endTime = Instant.parse("2026-08-29T08:15:00Z"),
            endZoneOffset = ZoneOffset.UTC,
            metadata = RecordMetadata(origin = "com.mi.health"),
            count = 200,
        )
        val batch1 = ExportBatch(
            header = BatchHeader(
                schemaVersion = 1,
                installationId = "inst-tie",
                batchId = "batch-tie",
                createdAt = Instant.parse("2026-08-30T00:00:00Z"),
                timeWindow = window,
                recordCount = 2,
                recordTypes = listOf("steps"),
            ),
            records = listOf(rec1, rec2),
        )
        val batch2 = batch1.copy(records = listOf(rec2, rec1))

        val json1 = serializer.serializeToJson(batch1)
        val json2 = serializer.serializeToJson(batch2)
        assertEquals(json1, json2)

        val ndjson1 = serializer.serializeToNdjson(batch1)
        val ndjson2 = serializer.serializeToNdjson(batch2)
        assertEquals(ndjson1, ndjson2)
    }
}
