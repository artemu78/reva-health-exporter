package dev.reva.healthexporter

import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.testing.FakeHealthConnectClient
import androidx.health.connect.client.testing.stubs.Stub
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Percentage
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

    private suspend fun insertTrustedRecords(client: FakeHealthConnectClient) {
        client.setPackageName("com.xiaomi.wearable")
        client.insertRecords(
            listOf<Record>(
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
                SleepSessionRecord(
                    startTime = Instant.parse("2026-08-29T00:30:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-08-29T07:30:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    title = "Night sleep",
                    metadata = Metadata.manualEntry(clientRecordId = "sleep_01"),
                ),
                OxygenSaturationRecord(
                    time = Instant.parse("2026-08-29T08:10:00Z"),
                    zoneOffset = ZoneOffset.UTC,
                    percentage = Percentage(97.0),
                    metadata = Metadata.manualEntry(clientRecordId = "oxygen_01"),
                ),
            ),
        )

        client.setPackageName("com.google.android.apps.fitness")
        client.insertRecords(
            listOf<Record>(
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
            ),
        )
    }

    @Test
    fun readerReadsAndMapsAllConfirmedTypes() = runBlocking {
        val client = FakeHealthConnectClient()
        insertTrustedRecords(client)

        val reader = HealthConnectExportReader(client = client)
        val records = reader.readRecords(window)

        assertEquals(6, records.size)
        val types = records.map { it.recordType }.toSet()
        assertEquals(
            setOf(
                "steps",
                "heart_rate",
                "distance",
                "total_calories_burned",
                "sleep_session",
                "oxygen_saturation",
            ),
            types,
        )
    }

    @Test
    fun mixedOriginStepsExportsOnlyTheXiaomiRecordWithoutMergingIt() = runBlocking {
        val client = FakeHealthConnectClient()
        val start = Instant.parse("2026-08-29T08:00:00Z")
        val end = Instant.parse("2026-08-29T08:15:00Z")

        client.setPackageName("com.google.android.apps.fitness")
        client.insertRecords(
            listOf(
                StepsRecord(
                    startTime = start,
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = end,
                    endZoneOffset = ZoneOffset.UTC,
                    count = 1_500,
                    metadata = Metadata.manualEntry(clientRecordId = "google-steps"),
                ),
            ),
        )
        client.setPackageName("com.xiaomi.wearable")
        client.insertRecords(
            listOf(
                StepsRecord(
                    startTime = start,
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = end,
                    endZoneOffset = ZoneOffset.UTC,
                    count = 1_500,
                    metadata = Metadata.manualEntry(clientRecordId = "xiaomi-steps"),
                ),
            ),
        )

        val records = HealthConnectExportReader(
            client = client,
            supportedRecordTypes = listOf(StepsRecord::class),
        ).readRecords(window)

        assertEquals(1, records.size)
        assertEquals("xiaomi-steps", records.single().metadata.clientRecordId)
        assertEquals("com.xiaomi.wearable", records.single().metadata.origin)
    }

    @Test
    fun disallowedOriginDoesNotFallBackWhenTrustedOriginHasNoRecords() = runBlocking {
        val client = FakeHealthConnectClient()
        client.setPackageName("com.google.android.apps.fitness")
        client.insertRecords(
            listOf(
                StepsRecord(
                    startTime = Instant.parse("2026-08-29T08:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-08-29T08:15:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    count = 900,
                    metadata = Metadata.manualEntry(clientRecordId = "google-only-steps"),
                ),
            ),
        )

        val records = HealthConnectExportReader(
            client = client,
            supportedRecordTypes = listOf(StepsRecord::class),
        ).readRecords(window)

        assertTrue(records.isEmpty())
    }

    @Test
    fun disallowedRecordReturnedByProviderIsStillRejected() = runBlocking {
        val originClient = FakeHealthConnectClient()
        originClient.setPackageName("com.google.android.apps.fitness")
        originClient.insertRecords(
            listOf(
                StepsRecord(
                    startTime = Instant.parse("2026-08-29T08:00:00Z"),
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = Instant.parse("2026-08-29T08:15:00Z"),
                    endZoneOffset = ZoneOffset.UTC,
                    count = 900,
                    metadata = Metadata.manualEntry(clientRecordId = "unexpected-google-steps"),
                ),
            ),
        )
        val disallowedRecord = originClient.readRecords(
            ReadRecordsRequest(
                StepsRecord::class,
                TimeRangeFilter.between(window.startInclusive, window.endExclusive),
            ),
        ).records.single()
        val client = FakeHealthConnectClient()
        client.overrides.readRecords = Stub {
            ReadRecordsResponse(records = listOf(disallowedRecord), pageToken = null)
        }

        val records = HealthConnectExportReader(
            client = client,
            supportedRecordTypes = listOf(StepsRecord::class),
        ).readRecords(window)

        assertTrue(records.isEmpty())
    }

    @Test
    fun distinctXiaomiRecordsWithEquivalentValuesRemainSeparate() = runBlocking {
        val client = FakeHealthConnectClient()
        val start = Instant.parse("2026-08-29T08:00:00Z")
        val end = Instant.parse("2026-08-29T08:15:00Z")
        client.setPackageName("com.xiaomi.wearable")
        client.insertRecords(
            listOf(
                StepsRecord(
                    start,
                    ZoneOffset.UTC,
                    end,
                    ZoneOffset.UTC,
                    1_500,
                    Metadata.manualEntry(clientRecordId = "xiaomi-steps-a"),
                ),
                StepsRecord(
                    start,
                    ZoneOffset.UTC,
                    end,
                    ZoneOffset.UTC,
                    1_500,
                    Metadata.manualEntry(clientRecordId = "xiaomi-steps-b"),
                ),
            ),
        )

        val records = HealthConnectExportReader(
            client = client,
            supportedRecordTypes = listOf(StepsRecord::class),
        ).readRecords(window)

        assertEquals(setOf("xiaomi-steps-a", "xiaomi-steps-b"), records.map { it.metadata.clientRecordId }.toSet())
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
        client.setPackageName("com.xiaomi.wearable")
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
        client.setPackageName("com.xiaomi.wearable")
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
        client.setPackageName("com.xiaomi.wearable")
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
