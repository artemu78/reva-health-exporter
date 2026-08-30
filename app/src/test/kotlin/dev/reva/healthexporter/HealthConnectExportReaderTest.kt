package dev.reva.healthexporter

import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.testing.FakeHealthConnectClient
import androidx.health.connect.client.testing.stubs.Stub
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthConnectExportReaderTest {

    private val window = TimeWindow(
        startInclusive = Instant.parse("2026-08-29T00:00:00Z"),
        endExclusive = Instant.parse("2026-08-30T00:00:00Z"),
    )

    private fun createConfirmedRecords(): List<Record> = listOf<Record>(
        StepsRecord(
            startTime = Instant.parse("2026-08-29T08:00:00Z"),
            startZoneOffset = ZoneOffset.UTC,
            endTime = Instant.parse("2026-08-29T08:15:00Z"),
            endZoneOffset = ZoneOffset.UTC,
            count = 1500,
            metadata = Metadata.manualEntry(clientRecordId = "steps_01"),
        ),
        HeartRateRecord(
            startTime = Instant.parse("2026-08-29T08:00:00Z"),
            startZoneOffset = ZoneOffset.UTC,
            endTime = Instant.parse("2026-08-29T08:05:00Z"),
            endZoneOffset = ZoneOffset.UTC,
            samples = listOf(
                HeartRateRecord.Sample(
                    time = Instant.parse("2026-08-29T08:01:00Z"),
                    beatsPerMinute = 72,
                ),
            ),
            metadata = Metadata.manualEntry(clientRecordId = "hr_01"),
        ),
        DistanceRecord(
            startTime = Instant.parse("2026-08-29T08:00:00Z"),
            startZoneOffset = ZoneOffset.UTC,
            endTime = Instant.parse("2026-08-29T08:15:00Z"),
            endZoneOffset = ZoneOffset.UTC,
            distance = Length.meters(1200.0),
            metadata = Metadata.manualEntry(clientRecordId = "dist_01"),
        ),
        TotalCaloriesBurnedRecord(
            startTime = Instant.parse("2026-08-29T08:00:00Z"),
            startZoneOffset = ZoneOffset.UTC,
            endTime = Instant.parse("2026-08-29T08:15:00Z"),
            endZoneOffset = ZoneOffset.UTC,
            energy = Energy.kilocalories(85.0),
            metadata = Metadata.manualEntry(clientRecordId = "cal_01"),
        ),
        SleepSessionRecord(
            startTime = Instant.parse("2026-08-29T00:30:00Z"),
            startZoneOffset = ZoneOffset.UTC,
            endTime = Instant.parse("2026-08-29T07:30:00Z"),
            endZoneOffset = ZoneOffset.UTC,
            title = "Night sleep",
            metadata = Metadata.manualEntry(clientRecordId = "sleep_01"),
        ),
    )

    @Test
    fun readerReadsAndMapsAllConfirmedTypes() = runBlocking {
        val client = FakeHealthConnectClient()
        client.setPackageName("com.mi.health")
        client.insertRecords(createConfirmedRecords())

        val reader = HealthConnectExportReader(client = client)
        val records = reader.readRecords(window)

        assertEquals(5, records.size)
        val types = records.map { it.recordType }.toSet()
        assertEquals(
            setOf("steps", "heart_rate", "distance", "total_calories_burned", "sleep_session"),
            types,
        )
    }

    @Test
    fun readerReturnsEmptyListWhenNoDataExists() = runBlocking {
        val client = FakeHealthConnectClient()
        val reader = HealthConnectExportReader(client = client)
        val records = reader.readRecords(window)

        assertTrue(records.isEmpty())
    }

    @Test
    fun readerDeduplicatesRecordsWithSameIdAndType() = runBlocking {
        val client = FakeHealthConnectClient()
        client.setPackageName("com.mi.health")
        val stepRecord1 = StepsRecord(
            startTime = Instant.parse("2026-08-29T08:00:00Z"),
            startZoneOffset = ZoneOffset.UTC,
            endTime = Instant.parse("2026-08-29T08:15:00Z"),
            endZoneOffset = ZoneOffset.UTC,
            count = 500,
            metadata = Metadata.manualEntry(clientRecordId = "steps_dup_01"),
        )
        val stepRecord2 = StepsRecord(
            startTime = Instant.parse("2026-08-29T08:00:00Z"),
            startZoneOffset = ZoneOffset.UTC,
            endTime = Instant.parse("2026-08-29T08:15:00Z"),
            endZoneOffset = ZoneOffset.UTC,
            count = 500,
            metadata = Metadata.manualEntry(clientRecordId = "steps_dup_01"),
        )
        client.insertRecords(listOf<Record>(stepRecord1, stepRecord2))

        val reader = HealthConnectExportReader(client = client)
        val records = reader.readRecords(window)

        assertEquals(1, records.size)
        assertEquals("steps_dup_01", records.first().metadata.clientRecordId)
    }

    @Test
    fun readerHandlesPaginationCorrectly() = runBlocking {
        val client = FakeHealthConnectClient()
        client.setPackageName("com.mi.health")
        val stepRecords: List<Record> = (1..25).map { i ->
            StepsRecord(
                startTime = Instant.parse("2026-08-29T08:00:00Z").plusSeconds((i * 60).toLong()),
                startZoneOffset = ZoneOffset.UTC,
                endTime = Instant.parse("2026-08-29T08:01:00Z").plusSeconds((i * 60).toLong()),
                endZoneOffset = ZoneOffset.UTC,
                count = 100,
                metadata = Metadata.manualEntry(clientRecordId = "step_page_$i"),
            )
        }
        client.insertRecords(stepRecords)

        // Read with small page size of 10 to force 3 pages
        val reader = HealthConnectExportReader(client = client, pageSize = 10)
        val records = reader.readRecords(window)

        assertEquals(25, records.size)
    }

    @Test
    fun exactWindowBoundariesAreRespected() = runBlocking {
        val client = FakeHealthConnectClient()
        client.setPackageName("com.mi.health")
        val recordAtStart = StepsRecord(
            startTime = window.startInclusive,
            startZoneOffset = ZoneOffset.UTC,
            endTime = window.startInclusive.plusSeconds(300),
            endZoneOffset = ZoneOffset.UTC,
            count = 100,
            metadata = Metadata.manualEntry(clientRecordId = "at_start"),
        )
        val recordInside = StepsRecord(
            startTime = window.startInclusive.plusSeconds(3600),
            startZoneOffset = ZoneOffset.UTC,
            endTime = window.startInclusive.plusSeconds(3900),
            endZoneOffset = ZoneOffset.UTC,
            count = 200,
            metadata = Metadata.manualEntry(clientRecordId = "inside"),
        )
        val recordAtEnd = StepsRecord(
            startTime = window.endExclusive,
            startZoneOffset = ZoneOffset.UTC,
            endTime = window.endExclusive.plusSeconds(300),
            endZoneOffset = ZoneOffset.UTC,
            count = 300,
            metadata = Metadata.manualEntry(clientRecordId = "at_end"),
        )
        client.insertRecords(listOf<Record>(recordAtStart, recordInside, recordAtEnd))

        val reader = HealthConnectExportReader(client = client)
        val records = reader.readRecords(window)

        assertEquals(2, records.size)
        val ids = records.mapNotNull { it.metadata.clientRecordId }.toSet()
        assertTrue(ids.contains("at_start"))
        assertTrue(ids.contains("inside"))
        assertFalse("Record exactly at endExclusive should not be included", ids.contains("at_end"))
    }

    @Test
    fun securityExceptionIsPropagated() {
        val client = FakeHealthConnectClient()
        client.overrides.readRecords = Stub {
            throw SecurityException("Health Connect permission denied")
        }

        val reader = HealthConnectExportReader(client = client)
        assertThrows(SecurityException::class.java) {
            runBlocking { reader.readRecords(window) }
        }
    }

    @Test
    fun cancellationExceptionIsRethrown() {
        val client = FakeHealthConnectClient()
        client.overrides.readRecords = Stub {
            throw CancellationException("Cancelled")
        }

        val reader = HealthConnectExportReader(client = client)
        assertThrows(CancellationException::class.java) {
            runBlocking { reader.readRecords(window) }
        }
    }
}
