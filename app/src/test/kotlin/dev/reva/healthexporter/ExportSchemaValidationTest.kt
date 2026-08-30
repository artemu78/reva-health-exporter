package dev.reva.healthexporter

import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Volume
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExportSchemaValidationTest {
    private val validMetadata = RecordMetadata(
        recordId = "rec-123",
        origin = "com.mi.health",
    )
    private val validWindow = TimeWindow(
        startInclusive = Instant.parse("2026-08-30T00:00:00Z"),
        endExclusive = Instant.parse("2026-08-31T00:00:00Z"),
    )

    @Test
    fun timeWindowStartMustBeBeforeEnd() {
        val error = assertThrows(InvalidExportSchemaException::class.java) {
            TimeWindow(
                startInclusive = Instant.parse("2026-08-30T10:00:00Z"),
                endExclusive = Instant.parse("2026-08-30T10:00:00Z"),
            )
        }
        assertEquals(true, error.message?.contains("strictly before"))
    }

    @Test
    fun blankRecordIdOrOriginThrowsValidationException() {
        assertThrows(InvalidExportSchemaException::class.java) {
            RecordMetadata(recordId = "", origin = "com.mi.health")
        }
        assertThrows(InvalidExportSchemaException::class.java) {
            RecordMetadata(recordId = "   ", origin = "com.mi.health")
        }
        assertThrows(InvalidExportSchemaException::class.java) {
            RecordMetadata(recordId = "rec-1", origin = "")
        }
        assertThrows(InvalidExportSchemaException::class.java) {
            RecordMetadata(recordId = "rec-1", origin = "  ")
        }
    }

    @Test
    fun stepsRejectsNegativeCountOrInvertedTime() {
        val start = Instant.parse("2026-08-30T10:00:00Z")
        val end = Instant.parse("2026-08-30T10:15:00Z")

        assertThrows(InvalidExportSchemaException::class.java) {
            CanonicalStepsRecord(
                startTime = end,
                startZoneOffset = null,
                endTime = start,
                endZoneOffset = null,
                metadata = validMetadata,
                count = 100,
            )
        }

        assertThrows(InvalidExportSchemaException::class.java) {
            CanonicalStepsRecord(
                startTime = start,
                startZoneOffset = null,
                endTime = end,
                endZoneOffset = null,
                metadata = validMetadata,
                count = -1,
            )
        }
    }

    @Test
    fun heartRateRejectsInvalidBpmOrSamplesOutOfBounds() {
        val start = Instant.parse("2026-08-30T10:00:00Z")
        val end = Instant.parse("2026-08-30T10:05:00Z")

        assertThrows(InvalidExportSchemaException::class.java) {
            HeartRateSample(time = start, beatsPerMinute = 0)
        }
        assertThrows(InvalidExportSchemaException::class.java) {
            HeartRateSample(time = start, beatsPerMinute = -5)
        }

        assertThrows(InvalidExportSchemaException::class.java) {
            CanonicalHeartRateRecord(
                startTime = start,
                startZoneOffset = null,
                endTime = end,
                endZoneOffset = null,
                metadata = validMetadata,
                samples = listOf(
                    HeartRateSample(time = start.minusSeconds(1), beatsPerMinute = 70),
                ),
            )
        }

        assertThrows(InvalidExportSchemaException::class.java) {
            CanonicalHeartRateRecord(
                startTime = start,
                startZoneOffset = null,
                endTime = end,
                endZoneOffset = null,
                metadata = validMetadata,
                samples = listOf(
                    HeartRateSample(time = end.plusSeconds(1), beatsPerMinute = 70),
                ),
            )
        }
    }

    @Test
    fun distanceRejectsNegativeOrNonFiniteValues() {
        val start = Instant.parse("2026-08-30T10:00:00Z")
        val end = Instant.parse("2026-08-30T10:15:00Z")

        assertThrows(InvalidExportSchemaException::class.java) {
            CanonicalDistanceRecord(
                startTime = start,
                startZoneOffset = null,
                endTime = end,
                endZoneOffset = null,
                metadata = validMetadata,
                distanceMeters = -0.1,
            )
        }

        assertThrows(InvalidExportSchemaException::class.java) {
            CanonicalDistanceRecord(
                startTime = start,
                startZoneOffset = null,
                endTime = end,
                endZoneOffset = null,
                metadata = validMetadata,
                distanceMeters = Double.NaN,
            )
        }

        assertThrows(InvalidExportSchemaException::class.java) {
            CanonicalDistanceRecord(
                startTime = start,
                startZoneOffset = null,
                endTime = end,
                endZoneOffset = null,
                metadata = validMetadata,
                distanceMeters = Double.POSITIVE_INFINITY,
            )
        }
    }

    @Test
    fun totalCaloriesRejectsNegativeOrNonFiniteValues() {
        val start = Instant.parse("2026-08-30T10:00:00Z")
        val end = Instant.parse("2026-08-30T10:15:00Z")

        assertThrows(InvalidExportSchemaException::class.java) {
            CanonicalTotalCaloriesBurnedRecord(
                startTime = start,
                startZoneOffset = null,
                endTime = end,
                endZoneOffset = null,
                metadata = validMetadata,
                energyKilocalories = -1.0,
            )
        }

        assertThrows(InvalidExportSchemaException::class.java) {
            CanonicalTotalCaloriesBurnedRecord(
                startTime = start,
                startZoneOffset = null,
                endTime = end,
                endZoneOffset = null,
                metadata = validMetadata,
                energyKilocalories = Double.NaN,
            )
        }
    }

    @Test
    fun sleepSessionRejectsStagesOutsideBoundsOrInvertedTime() {
        val start = Instant.parse("2026-08-29T23:00:00Z")
        val end = Instant.parse("2026-08-30T07:00:00Z")

        assertThrows(InvalidExportSchemaException::class.java) {
            SleepStage(startTime = end, endTime = start, stage = 1)
        }

        assertThrows(InvalidExportSchemaException::class.java) {
            CanonicalSleepSessionRecord(
                startTime = start,
                startZoneOffset = null,
                endTime = end,
                endZoneOffset = null,
                metadata = validMetadata,
                stages = listOf(
                    SleepStage(
                        startTime = start.minusSeconds(10),
                        endTime = start.plusSeconds(3600),
                        stage = 1,
                    ),
                ),
            )
        }
    }

    @Test
    fun exerciseSessionRejectsInvalidSegmentsLapsOrRepetitions() {
        val start = Instant.parse("2026-08-30T08:00:00Z")
        val end = Instant.parse("2026-08-30T08:45:00Z")

        assertThrows(InvalidExportSchemaException::class.java) {
            ExerciseSegmentModel(startTime = end, endTime = start, segmentType = 1, repetitions = 0)
        }

        assertThrows(InvalidExportSchemaException::class.java) {
            ExerciseSegmentModel(startTime = start, endTime = end, segmentType = 1, repetitions = -1)
        }

        assertThrows(InvalidExportSchemaException::class.java) {
            ExerciseLapModel(startTime = end, endTime = start, lengthMeters = 100.0)
        }

        assertThrows(InvalidExportSchemaException::class.java) {
            ExerciseLapModel(startTime = start, endTime = end, lengthMeters = -10.0)
        }

        assertThrows(InvalidExportSchemaException::class.java) {
            CanonicalExerciseSessionRecord(
                startTime = start,
                startZoneOffset = null,
                endTime = end,
                endZoneOffset = null,
                metadata = validMetadata,
                exerciseType = 1,
                segments = listOf(
                    ExerciseSegmentModel(
                        startTime = start,
                        endTime = end.plusSeconds(10),
                        segmentType = 1,
                    ),
                ),
            )
        }
    }

    @Test
    fun oxygenSaturationRejectsValuesOutsideRange() {
        val time = Instant.parse("2026-08-30T10:00:00Z")

        assertThrows(InvalidExportSchemaException::class.java) {
            CanonicalOxygenSaturationRecord(
                startTime = time,
                startZoneOffset = null,
                endTime = time,
                endZoneOffset = null,
                metadata = validMetadata,
                percentage = -0.1,
            )
        }

        assertThrows(InvalidExportSchemaException::class.java) {
            CanonicalOxygenSaturationRecord(
                startTime = time,
                startZoneOffset = null,
                endTime = time,
                endZoneOffset = null,
                metadata = validMetadata,
                percentage = 100.1,
            )
        }
    }

    @Test
    fun restingHeartRateRejectsNonPositiveBpm() {
        val time = Instant.parse("2026-08-30T10:00:00Z")

        assertThrows(InvalidExportSchemaException::class.java) {
            CanonicalRestingHeartRateRecord(
                startTime = time,
                startZoneOffset = null,
                endTime = time,
                endZoneOffset = null,
                metadata = validMetadata,
                beatsPerMinute = 0,
            )
        }
    }

    @Test
    fun batchHeaderRejectsUnsupportedVersionOrMismatchedRecordTypes() {
        val now = Instant.parse("2026-08-30T12:00:00Z")

        assertThrows(InvalidExportSchemaException::class.java) {
            BatchHeader(
                schemaVersion = 2,
                installationId = "inst-1",
                batchId = "batch-1",
                createdAt = now,
                timeWindow = validWindow,
                recordCount = 0,
                recordTypes = emptyList(),
            )
        }

        assertThrows(InvalidExportSchemaException::class.java) {
            BatchHeader(
                schemaVersion = 1,
                installationId = "inst-1",
                batchId = "batch-1",
                createdAt = now,
                timeWindow = validWindow,
                recordCount = -1,
                recordTypes = emptyList(),
            )
        }
    }

    @Test
    fun exportBatchRejectsMismatchedRecordCountOrTypes() {
        val now = Instant.parse("2026-08-30T12:00:00Z")
        val stepRecord = CanonicalStepsRecord(
            startTime = Instant.parse("2026-08-30T10:00:00Z"),
            startZoneOffset = ZoneOffset.UTC,
            endTime = Instant.parse("2026-08-30T10:15:00Z"),
            endZoneOffset = ZoneOffset.UTC,
            metadata = validMetadata,
            count = 100,
        )

        val headerCountMismatch = BatchHeader(
            schemaVersion = 1,
            installationId = "inst-1",
            batchId = "batch-1",
            createdAt = now,
            timeWindow = validWindow,
            recordCount = 2,
            recordTypes = listOf("steps"),
        )
        assertThrows(InvalidExportSchemaException::class.java) {
            ExportBatch(headerCountMismatch, listOf(stepRecord))
        }

        val headerTypeMismatch = BatchHeader(
            schemaVersion = 1,
            installationId = "inst-1",
            batchId = "batch-1",
            createdAt = now,
            timeWindow = validWindow,
            recordCount = 1,
            recordTypes = listOf("heart_rate"),
        )
        assertThrows(InvalidExportSchemaException::class.java) {
            ExportBatch(headerTypeMismatch, listOf(stepRecord))
        }
    }

    @Test
    fun mapperRejectsUnsupportedHealthConnectRecordType() {
        val mapper = HealthRecordMapper()
        val start = Instant.parse("2026-08-30T10:00:00Z")
        val unsupportedRecord = HydrationRecord(
            startTime = start,
            startZoneOffset = null,
            endTime = start.plusSeconds(60),
            endZoneOffset = null,
            volume = Volume.liters(0.5),
            metadata = Metadata.manualEntry(),
        )

        val error = assertThrows(InvalidExportSchemaException::class.java) {
            mapper.mapRecord(unsupportedRecord)
        }
        assertEquals(true, error.message?.contains("Unsupported Health Connect record type"))
    }
}
