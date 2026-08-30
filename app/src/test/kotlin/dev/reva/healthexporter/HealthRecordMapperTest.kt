package dev.reva.healthexporter

import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseLap
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.testing.FakeHealthConnectClient
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Percentage
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HealthRecordMapperTest {
    private val mapper = HealthRecordMapper()

    @Test
    fun mapsStepsRecordWithFullMetadata() = runBlocking {
        val client = FakeHealthConnectClient()
        client.setPackageName("com.mi.health")
        val start = Instant.parse("2026-08-30T10:00:00Z")
        val end = Instant.parse("2026-08-30T10:15:00Z")
        val response = client.insertRecords(
            listOf(
                StepsRecord(
                    startTime = start,
                    startZoneOffset = ZoneOffset.ofHours(3),
                    endTime = end,
                    endZoneOffset = ZoneOffset.ofHours(3),
                    count = 1250,
                    metadata = Metadata.activelyRecorded(
                        device = Device(
                            type = Device.TYPE_FITNESS_BAND,
                            manufacturer = "Xiaomi",
                            model = "Smart Band 9",
                        ),
                        clientRecordId = "client-step-1",
                        clientRecordVersion = 42L,
                    ),
                ),
            ),
        )

        val readResponse = client.readRecords(
            ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start.minusSeconds(1), end.plusSeconds(1)),
            ),
        )
        val readRecord = readResponse.records.first()
        val canonical = mapper.mapRecord(readRecord) as CanonicalStepsRecord

        assertEquals("steps", canonical.recordType)
        assertEquals(response.recordIdsList.first(), canonical.metadata.recordId)
        assertEquals("com.mi.health", canonical.metadata.origin)
        assertEquals("client-step-1", canonical.metadata.clientRecordId)
        assertEquals(42L, canonical.metadata.clientRecordVersion)
        assertEquals(Metadata.RECORDING_METHOD_ACTIVELY_RECORDED, canonical.metadata.recordingMethod)
        assertEquals(DeviceMetadata("Xiaomi", "Smart Band 9", Device.TYPE_FITNESS_BAND), canonical.metadata.device)
        assertNotNull(canonical.metadata.lastModifiedTime)
        assertEquals(start, canonical.startTime)
        assertEquals(ZoneOffset.ofHours(3), canonical.startZoneOffset)
        assertEquals(end, canonical.endTime)
        assertEquals(ZoneOffset.ofHours(3), canonical.endZoneOffset)
        assertEquals(1250L, canonical.count)
    }

    @Test
    fun mapsStepsRecordWithMinimalMetadata() = runBlocking {
        val client = FakeHealthConnectClient()
        client.setPackageName("com.mi.health")
        val start = Instant.parse("2026-08-30T10:00:00Z")
        val end = Instant.parse("2026-08-30T10:15:00Z")
        val response = client.insertRecords(
            listOf(
                StepsRecord(
                    startTime = start,
                    startZoneOffset = null,
                    endTime = end,
                    endZoneOffset = null,
                    count = 500,
                    metadata = Metadata.manualEntry(),
                ),
            ),
        )

        val readRecord = client.readRecords(
            ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start.minusSeconds(1), end.plusSeconds(1)),
            ),
        ).records.first()

        val canonical = mapper.mapRecord(readRecord) as CanonicalStepsRecord

        assertEquals("steps", canonical.recordType)
        assertEquals(response.recordIdsList.first(), canonical.metadata.recordId)
        assertEquals("com.mi.health", canonical.metadata.origin)
        assertNull(canonical.metadata.clientRecordId)
        assertNull(canonical.metadata.clientRecordVersion)
        assertNull(canonical.metadata.device)
        assertNull(canonical.startZoneOffset)
        assertNull(canonical.endZoneOffset)
        assertEquals(500L, canonical.count)
    }

    @Test
    fun mapsHeartRateRecordWithSeriesSamples() = runBlocking {
        val client = FakeHealthConnectClient()
        client.setPackageName("com.mi.health")
        val start = Instant.parse("2026-08-30T10:00:00Z")
        val end = Instant.parse("2026-08-30T10:05:00Z")
        val sample1 = HeartRateRecord.Sample(time = Instant.parse("2026-08-30T10:01:00Z"), beatsPerMinute = 72)
        val sample2 = HeartRateRecord.Sample(time = Instant.parse("2026-08-30T10:03:00Z"), beatsPerMinute = 78)
        client.insertRecords(
            listOf(
                HeartRateRecord(
                    startTime = start,
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = end,
                    endZoneOffset = ZoneOffset.UTC,
                    samples = listOf(sample1, sample2),
                    metadata = Metadata.manualEntry(),
                ),
            ),
        )

        val readRecord = client.readRecords(
            ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start.minusSeconds(1), end.plusSeconds(1)),
            ),
        ).records.first()

        val canonical = mapper.mapRecord(readRecord) as CanonicalHeartRateRecord

        assertEquals("heart_rate", canonical.recordType)
        assertEquals("com.mi.health", canonical.metadata.origin)
        assertEquals(2, canonical.samples.size)
        assertEquals(HeartRateSample(Instant.parse("2026-08-30T10:01:00Z"), 72L), canonical.samples[0])
        assertEquals(HeartRateSample(Instant.parse("2026-08-30T10:03:00Z"), 78L), canonical.samples[1])
    }

    @Test
    fun mapsDistanceRecordWithMetersUnitConversion() = runBlocking {
        val client = FakeHealthConnectClient()
        client.setPackageName("com.mi.health")
        val start = Instant.parse("2026-08-30T10:00:00Z")
        val end = Instant.parse("2026-08-30T10:15:00Z")
        client.insertRecords(
            listOf(
                DistanceRecord(
                    startTime = start,
                    startZoneOffset = null,
                    endTime = end,
                    endZoneOffset = null,
                    distance = Length.kilometers(1.5),
                    metadata = Metadata.manualEntry(),
                ),
                DistanceRecord(
                    startTime = start.plusSeconds(3600),
                    startZoneOffset = null,
                    endTime = end.plusSeconds(3600),
                    endZoneOffset = null,
                    distance = Length.miles(1.0),
                    metadata = Metadata.manualEntry(),
                ),
            ),
        )

        val records = client.readRecords(
            ReadRecordsRequest(
                recordType = DistanceRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start.minusSeconds(1), end.plusSeconds(4000)),
            ),
        ).records

        val canonicalKm = mapper.mapRecord(records.first { it.distance.inMeters in 1499.0..1501.0 }) as CanonicalDistanceRecord
        val canonicalMi = mapper.mapRecord(records.first { it.distance.inMeters in 1609.0..1610.0 }) as CanonicalDistanceRecord

        assertEquals(1500.0, canonicalKm.distanceMeters, 0.001)
        assertEquals(1609.34, canonicalMi.distanceMeters, 0.01)
    }

    @Test
    fun mapsTotalCaloriesBurnedWithKilocaloriesUnitConversion() = runBlocking {
        val client = FakeHealthConnectClient()
        client.setPackageName("com.mi.health")
        val start = Instant.parse("2026-08-30T10:00:00Z")
        val end = Instant.parse("2026-08-30T10:15:00Z")
        client.insertRecords(
            listOf(
                TotalCaloriesBurnedRecord(
                    startTime = start,
                    startZoneOffset = null,
                    endTime = end,
                    endZoneOffset = null,
                    energy = Energy.joules(4184.0),
                    metadata = Metadata.manualEntry(),
                ),
                TotalCaloriesBurnedRecord(
                    startTime = start.plusSeconds(3600),
                    startZoneOffset = null,
                    endTime = end.plusSeconds(3600),
                    endZoneOffset = null,
                    energy = Energy.calories(50000.0),
                    metadata = Metadata.manualEntry(),
                ),
            ),
        )

        val records = client.readRecords(
            ReadRecordsRequest(
                recordType = TotalCaloriesBurnedRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start.minusSeconds(1), end.plusSeconds(4000)),
            ),
        ).records

        val canonicalJoules = mapper.mapRecord(records.first { it.energy.inKilocalories in 0.99..1.01 }) as CanonicalTotalCaloriesBurnedRecord
        val canonicalCal = mapper.mapRecord(records.first { it.energy.inKilocalories in 49.99..50.01 }) as CanonicalTotalCaloriesBurnedRecord

        assertEquals(1.0, canonicalJoules.energyKilocalories, 0.001)
        assertEquals(50.0, canonicalCal.energyKilocalories, 0.001)
    }

    @Test
    fun mapsSleepSessionRecordWithStagesAndNotes() = runBlocking {
        val client = FakeHealthConnectClient()
        client.setPackageName("com.mi.health")
        val start = Instant.parse("2026-08-29T23:00:00Z")
        val end = Instant.parse("2026-08-30T07:00:00Z")
        val stage1 = SleepSessionRecord.Stage(
            startTime = Instant.parse("2026-08-29T23:00:00Z"),
            endTime = Instant.parse("2026-08-30T00:00:00Z"),
            stage = SleepSessionRecord.STAGE_TYPE_LIGHT,
        )
        val stage2 = SleepSessionRecord.Stage(
            startTime = Instant.parse("2026-08-30T00:00:00Z"),
            endTime = Instant.parse("2026-08-30T02:00:00Z"),
            stage = SleepSessionRecord.STAGE_TYPE_DEEP,
        )
        client.insertRecords(
            listOf(
                SleepSessionRecord(
                    startTime = start,
                    startZoneOffset = ZoneOffset.ofHours(3),
                    endTime = end,
                    endZoneOffset = ZoneOffset.ofHours(3),
                    title = "Deep Rest",
                    notes = "Felt energized",
                    stages = listOf(stage1, stage2),
                    metadata = Metadata.manualEntry(),
                ),
            ),
        )

        val readRecord = client.readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start.minusSeconds(1), end.plusSeconds(1)),
            ),
        ).records.first()

        val canonical = mapper.mapRecord(readRecord) as CanonicalSleepSessionRecord

        assertEquals("sleep_session", canonical.recordType)
        assertEquals("Deep Rest", canonical.title)
        assertEquals("Felt energized", canonical.notes)
        assertEquals(2, canonical.stages.size)
        assertEquals(SleepStage(stage1.startTime, stage1.endTime, SleepSessionRecord.STAGE_TYPE_LIGHT), canonical.stages[0])
        assertEquals(SleepStage(stage2.startTime, stage2.endTime, SleepSessionRecord.STAGE_TYPE_DEEP), canonical.stages[1])
    }

    @Test
    fun mapsExerciseSessionWithSegmentsAndLaps() = runBlocking {
        val client = FakeHealthConnectClient()
        client.setPackageName("com.mi.health")
        val start = Instant.parse("2026-08-30T08:00:00Z")
        val end = Instant.parse("2026-08-30T08:45:00Z")
        val segment = ExerciseSegment(
            startTime = Instant.parse("2026-08-30T08:00:00Z"),
            endTime = Instant.parse("2026-08-30T08:20:00Z"),
            segmentType = ExerciseSegment.EXERCISE_SEGMENT_TYPE_WALKING,
            repetitions = 0,
        )
        val lap = ExerciseLap(
            startTime = Instant.parse("2026-08-30T08:00:00Z"),
            endTime = Instant.parse("2026-08-30T08:15:00Z"),
            length = Length.meters(1200.0),
        )
        client.insertRecords(
            listOf(
                ExerciseSessionRecord(
                    startTime = start,
                    startZoneOffset = ZoneOffset.ofHours(3),
                    endTime = end,
                    endZoneOffset = ZoneOffset.ofHours(3),
                    exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
                    title = "Morning Park Walk",
                    notes = "Sunny weather",
                    segments = listOf(segment),
                    laps = listOf(lap),
                    metadata = Metadata.manualEntry(),
                ),
            ),
        )

        val readRecord = client.readRecords(
            ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start.minusSeconds(1), end.plusSeconds(1)),
            ),
        ).records.first()

        val canonical = mapper.mapRecord(readRecord) as CanonicalExerciseSessionRecord

        assertEquals("exercise_session", canonical.recordType)
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_WALKING, canonical.exerciseType)
        assertEquals("Morning Park Walk", canonical.title)
        assertEquals("Sunny weather", canonical.notes)
        assertEquals(1, canonical.segments.size)
        assertEquals(ExerciseSegmentModel(segment.startTime, segment.endTime, segment.segmentType, 0), canonical.segments[0])
        assertEquals(1, canonical.laps.size)
        assertEquals(ExerciseLapModel(lap.startTime, lap.endTime, 1200.0), canonical.laps[0])
    }

    @Test
    fun mapsRestingHeartRateAndOxygenSaturationDeferredTypes() = runBlocking {
        val client = FakeHealthConnectClient()
        client.setPackageName("com.mi.health")
        val time = Instant.parse("2026-08-30T09:00:00Z")
        client.insertRecords(
            listOf(
                RestingHeartRateRecord(
                    time = time,
                    zoneOffset = ZoneOffset.UTC,
                    beatsPerMinute = 58,
                    metadata = Metadata.manualEntry(),
                ),
            ),
        )
        client.insertRecords(
            listOf(
                OxygenSaturationRecord(
                    time = time,
                    zoneOffset = ZoneOffset.UTC,
                    percentage = Percentage(98.5),
                    metadata = Metadata.manualEntry(),
                ),
            ),
        )

        val rhrRecord = client.readRecords(
            ReadRecordsRequest(
                recordType = RestingHeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(time.minusSeconds(1), time.plusSeconds(1)),
            ),
        ).records.first()

        val spo2Record = client.readRecords(
            ReadRecordsRequest(
                recordType = OxygenSaturationRecord::class,
                timeRangeFilter = TimeRangeFilter.between(time.minusSeconds(1), time.plusSeconds(1)),
            ),
        ).records.first()

        val canonicalRhr = mapper.mapRecord(rhrRecord) as CanonicalRestingHeartRateRecord
        val canonicalSpo2 = mapper.mapRecord(spo2Record) as CanonicalOxygenSaturationRecord

        assertEquals(58L, canonicalRhr.beatsPerMinute)
        assertEquals(time, canonicalRhr.startTime)
        assertEquals(time, canonicalRhr.endTime)
        assertEquals(98.5, canonicalSpo2.percentage, 0.001)
        assertEquals(time, canonicalSpo2.startTime)
        assertEquals(time, canonicalSpo2.endTime)
    }
}
