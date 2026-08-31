package dev.reva.healthexporter

import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import java.io.InputStream
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SchemaV1CompatibilityTest {
    private val serializer = ExportBatchSerializer()

    @Test
    fun frozenV1GoldenBatchMatchesExpectedModel() {
        val stream = getFixtureStream("fixtures/schema_v1/v1_golden_batch.ndjson")
        val rawNdjson = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }

        val batch = serializer.parseNdjson(rawNdjson)

        // 1. Assert header
        assertEquals(1, batch.header.schemaVersion)
        assertEquals("00000000-0000-4000-8000-000000000001", batch.header.installationId)
        assertEquals("11111111-1111-4111-8111-111111111111", batch.header.batchId)
        assertEquals(Instant.parse("2026-08-30T12:00:00Z"), batch.header.createdAt)
        assertEquals(Instant.parse("2026-08-29T00:00:00Z"), batch.header.timeWindow.startInclusive)
        assertEquals(Instant.parse("2026-08-30T00:00:00Z"), batch.header.timeWindow.endExclusive)
        assertEquals(6, batch.header.recordCount)
        assertEquals(
            listOf("distance", "exercise_session", "heart_rate", "sleep_session", "steps", "total_calories_burned"),
            batch.header.recordTypes,
        )

        // 2. Assert Steps record
        val steps = batch.records.filterIsInstance<CanonicalStepsRecord>().first()
        assertEquals("rec_steps_golden_01", steps.metadata.recordId)
        assertEquals("com.mi.health", steps.metadata.origin)
        assertEquals("client_steps_01", steps.metadata.clientRecordId)
        assertEquals(1L, steps.metadata.clientRecordVersion)
        assertEquals(Metadata.RECORDING_METHOD_ACTIVELY_RECORDED, steps.metadata.recordingMethod)
        assertEquals(DeviceMetadata("Xiaomi", "Smart Band 9", Device.TYPE_FITNESS_BAND), steps.metadata.device)
        assertEquals(Instant.parse("2026-08-29T08:15:02Z"), steps.metadata.lastModifiedTime)
        assertEquals(Instant.parse("2026-08-29T08:00:00Z"), steps.startTime)
        assertEquals(ZoneOffset.ofHours(3), steps.startZoneOffset)
        assertEquals(Instant.parse("2026-08-29T08:15:00Z"), steps.endTime)
        assertEquals(ZoneOffset.ofHours(3), steps.endZoneOffset)
        assertEquals(1250L, steps.count)

        // 3. Assert Heart Rate record
        val hr = batch.records.filterIsInstance<CanonicalHeartRateRecord>().first()
        assertEquals("rec_hr_golden_01", hr.metadata.recordId)
        assertEquals("com.mi.health", hr.metadata.origin)
        assertEquals(2, hr.samples.size)
        assertEquals(HeartRateSample(Instant.parse("2026-08-29T08:01:00Z"), 72L), hr.samples[0])
        assertEquals(HeartRateSample(Instant.parse("2026-08-29T08:03:00Z"), 78L), hr.samples[1])

        // 4. Assert Distance record
        val dist = batch.records.filterIsInstance<CanonicalDistanceRecord>().first()
        assertEquals("rec_dist_golden_01", dist.metadata.recordId)
        assertEquals(850.5, dist.distanceMeters, 0.001)

        // 5. Assert Calories record
        val cal = batch.records.filterIsInstance<CanonicalTotalCaloriesBurnedRecord>().first()
        assertEquals("rec_cal_golden_01", cal.metadata.recordId)
        assertEquals(45.2, cal.energyKilocalories, 0.001)

        // 6. Assert Sleep record
        val sleep = batch.records.filterIsInstance<CanonicalSleepSessionRecord>().first()
        assertEquals("rec_sleep_golden_01", sleep.metadata.recordId)
        assertEquals("Night Sleep", sleep.title)
        assertNull(sleep.notes)
        assertEquals(3, sleep.stages.size)
        assertEquals(SleepStage(Instant.parse("2026-08-29T00:30:00Z"), Instant.parse("2026-08-29T01:30:00Z"), SleepSessionRecord.STAGE_TYPE_LIGHT), sleep.stages[0])
        assertEquals(SleepStage(Instant.parse("2026-08-29T01:30:00Z"), Instant.parse("2026-08-29T03:30:00Z"), SleepSessionRecord.STAGE_TYPE_DEEP), sleep.stages[1])
        assertEquals(SleepStage(Instant.parse("2026-08-29T03:30:00Z"), Instant.parse("2026-08-29T05:00:00Z"), SleepSessionRecord.STAGE_TYPE_REM), sleep.stages[2])

        // 7. Assert Exercise record
        val exercise = batch.records.filterIsInstance<CanonicalExerciseSessionRecord>().first()
        assertEquals("rec_ex_golden_01", exercise.metadata.recordId)
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_WALKING, exercise.exerciseType)
        assertEquals("Evening Outdoor Walk", exercise.title)
        assertEquals(1, exercise.segments.size)
        assertEquals(ExerciseSegmentModel(Instant.parse("2026-08-29T18:00:00Z"), Instant.parse("2026-08-29T18:45:00Z"), ExerciseSegment.EXERCISE_SEGMENT_TYPE_WALKING, 0), exercise.segments[0])
        assertEquals(2, exercise.laps.size)
        assertEquals(ExerciseLapModel(Instant.parse("2026-08-29T18:00:00Z"), Instant.parse("2026-08-29T18:22:30Z"), 1500.0), exercise.laps[0])
        assertEquals(ExerciseLapModel(Instant.parse("2026-08-29T18:22:30Z"), Instant.parse("2026-08-29T18:45:00Z"), 1500.0), exercise.laps[1])

        // 8. Assert Re-serialization produces deterministic output with stripped metadata
        val reSerialized = serializer.serializeToNdjson(batch)
        assertFalse(reSerialized.contains("\"device\""))
        assertFalse(reSerialized.contains("\"recordId\""))
        assertFalse(reSerialized.contains("\"clientRecordId\""))
    }

    @Test
    fun frozenV1GoldenGzipBatchMatchesAndDecompresses() {
        val rawNdjson = getFixtureStream("fixtures/schema_v1/v1_golden_batch.ndjson")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

        // 1. Independent raw decompression
        val independentlyDecompressed = GZIPInputStream(getFixtureStream("fixtures/schema_v1/v1_golden_batch.ndjson.gz"))
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        assertEquals(rawNdjson, independentlyDecompressed)

        // 2. Serializer helper decompression and parse
        val batch = serializer.decompressAndParse(getFixtureStream("fixtures/schema_v1/v1_golden_batch.ndjson.gz"))
        assertEquals(6, batch.records.size)
        assertEquals(1, batch.header.schemaVersion)
    }

    @Test
    fun frozenV1EmptyBatchParsesSuccessfully() {
        val rawNdjson = getFixtureStream("fixtures/schema_v1/v1_empty_batch.ndjson")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

        val batch = serializer.parseNdjson(rawNdjson)
        assertEquals(0, batch.records.size)
        assertEquals(0, batch.header.recordCount)
        assertEquals(emptyList<String>(), batch.header.recordTypes)

        val reSerialized = serializer.serializeToNdjson(batch)
        assertEquals(rawNdjson, reSerialized)
    }

    @Test
    fun frozenV1GoldenJsonBatchMatchesExpectedModel() {
        val stream = getFixtureStream("fixtures/schema_v1/v1_golden_batch.json")
        val rawJson = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }

        val batch = serializer.parseJson(rawJson)

        // 1. Assert header
        assertEquals(1, batch.header.schemaVersion)
        assertEquals("00000000-0000-4000-8000-000000000001", batch.header.installationId)
        assertEquals("11111111-1111-4111-8111-111111111111", batch.header.batchId)
        assertEquals(Instant.parse("2026-08-30T12:00:00Z"), batch.header.createdAt)
        assertEquals(Instant.parse("2026-08-29T00:00:00Z"), batch.header.timeWindow.startInclusive)
        assertEquals(Instant.parse("2026-08-30T00:00:00Z"), batch.header.timeWindow.endExclusive)
        assertEquals(6, batch.header.recordCount)
        assertEquals(
            listOf("distance", "exercise_session", "heart_rate", "sleep_session", "steps", "total_calories_burned"),
            batch.header.recordTypes,
        )

        // 2. Assert Steps record
        val steps = batch.records.filterIsInstance<CanonicalStepsRecord>().first()
        assertEquals("com.mi.health", steps.metadata.origin)
        assertEquals(Instant.parse("2026-08-29T08:00:00Z"), steps.startTime)
        assertEquals(ZoneOffset.ofHours(3), steps.startZoneOffset)
        assertEquals(Instant.parse("2026-08-29T08:15:00Z"), steps.endTime)
        assertEquals(ZoneOffset.ofHours(3), steps.endZoneOffset)
        assertEquals(1250L, steps.count)

        // 3. Assert Heart Rate record
        val hr = batch.records.filterIsInstance<CanonicalHeartRateRecord>().first()
        assertEquals("com.mi.health", hr.metadata.origin)
        assertEquals(2, hr.samples.size)
        assertEquals(HeartRateSample(Instant.parse("2026-08-29T08:01:00Z"), 72L), hr.samples[0])
        assertEquals(HeartRateSample(Instant.parse("2026-08-29T08:03:00Z"), 78L), hr.samples[1])

        // 4. Assert Distance record
        val dist = batch.records.filterIsInstance<CanonicalDistanceRecord>().first()
        assertEquals("com.mi.health", dist.metadata.origin)
        assertEquals(850.5, dist.distanceMeters, 0.001)

        // 5. Assert Calories record
        val cal = batch.records.filterIsInstance<CanonicalTotalCaloriesBurnedRecord>().first()
        assertEquals("com.mi.health", cal.metadata.origin)
        assertEquals(45.2, cal.energyKilocalories, 0.001)

        // 6. Assert Sleep record
        val sleep = batch.records.filterIsInstance<CanonicalSleepSessionRecord>().first()
        assertEquals("com.mi.health", sleep.metadata.origin)
        assertEquals("Night Sleep", sleep.title)
        assertNull(sleep.notes)
        assertEquals(3, sleep.stages.size)
        assertEquals(SleepStage(Instant.parse("2026-08-29T00:30:00Z"), Instant.parse("2026-08-29T01:30:00Z"), SleepSessionRecord.STAGE_TYPE_LIGHT), sleep.stages[0])
        assertEquals(SleepStage(Instant.parse("2026-08-29T01:30:00Z"), Instant.parse("2026-08-29T03:30:00Z"), SleepSessionRecord.STAGE_TYPE_DEEP), sleep.stages[1])
        assertEquals(SleepStage(Instant.parse("2026-08-29T03:30:00Z"), Instant.parse("2026-08-29T05:00:00Z"), SleepSessionRecord.STAGE_TYPE_REM), sleep.stages[2])

        // 7. Assert Exercise record
        val exercise = batch.records.filterIsInstance<CanonicalExerciseSessionRecord>().first()
        assertEquals("com.mi.health", exercise.metadata.origin)
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_WALKING, exercise.exerciseType)
        assertEquals("Evening Outdoor Walk", exercise.title)
        assertEquals(1, exercise.segments.size)
        assertEquals(ExerciseSegmentModel(Instant.parse("2026-08-29T18:00:00Z"), Instant.parse("2026-08-29T18:45:00Z"), ExerciseSegment.EXERCISE_SEGMENT_TYPE_WALKING, 0), exercise.segments[0])
        assertEquals(2, exercise.laps.size)
        assertEquals(ExerciseLapModel(Instant.parse("2026-08-29T18:00:00Z"), Instant.parse("2026-08-29T18:22:30Z"), 1500.0), exercise.laps[0])
        assertEquals(ExerciseLapModel(Instant.parse("2026-08-29T18:22:30Z"), Instant.parse("2026-08-29T18:45:00Z"), 1500.0), exercise.laps[1])

        // 8. Assert Re-serialization is deterministic and stripped
        val reSerialized = serializer.serializeToJson(batch)
        assertFalse(reSerialized.contains("\"device\""))
        assertFalse(reSerialized.contains("\"recordId\""))
        assertFalse(reSerialized.contains("\"clientRecordId\""))
        assertFalse(reSerialized.contains("\"recordingMethod\""))
    }

    @Test
    fun frozenV1EmptyJsonBatchParsesSuccessfully() {
        val rawJson = getFixtureStream("fixtures/schema_v1/v1_empty_batch.json")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

        val batch = serializer.parseJson(rawJson)
        assertEquals(0, batch.records.size)
        assertEquals(0, batch.header.recordCount)
        assertEquals(emptyList<String>(), batch.header.recordTypes)

        val reSerialized = serializer.serializeToJson(batch)
        val reParsed = serializer.parseJson(reSerialized)
        assertEquals(batch, reParsed)
    }

    private fun getFixtureStream(path: String): InputStream {
        val stream = javaClass.classLoader?.getResourceAsStream(path)
        assertNotNull("Fixture not found on classpath: $path", stream)
        return stream!!
    }
}
