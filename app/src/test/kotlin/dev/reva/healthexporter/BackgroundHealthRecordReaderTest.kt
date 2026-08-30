package dev.reva.healthexporter

import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
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
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundHealthRecordReaderTest {
    private val clock = object : DiagnosticClock {
        override fun now(zoneId: ZoneId): ZonedDateTime =
            ZonedDateTime.parse("2026-08-30T12:00:00Z[UTC]")
    }

    private val executionInstant: Instant = Instant.parse("2026-08-30T12:00:00Z")
    private val window = ProbeTimeWindow(
        startInclusive = Instant.parse("2026-08-29T12:00:00Z"),
        endExclusive = Instant.parse("2026-08-30T12:00:00Z"),
    )

    @Test
    fun successfulReadAggregatesAllConfirmedTypes() = runBlocking {
        val client = FakeHealthConnectClient()
        client.setPackageName("com.mi.health")
        client.insertRecords(createConfirmedRecords())

        val reader = BackgroundHealthRecordReader(client = client, clock = clock)
        val result = reader.readConfirmedRecords(window)

        assertEquals(BackgroundReadOutcome.SUCCESS, result.outcome)
        assertEquals(5, result.readTypesCount)
        assertEquals(5, result.totalRecords)
        assertEquals(setOf("com.mi.health"), result.dataOrigins)
        assertEquals(executionInstant, result.executionTimestamp)
        assertTrue(result.message.contains("5 confirmed record types"))
    }

    @Test
    fun emptyConfirmedDataProducesSuccessfulResult() = runBlocking {
        val client = FakeHealthConnectClient()
        val reader = BackgroundHealthRecordReader(client = client, clock = clock)
        val result = reader.readConfirmedRecords(window)

        assertEquals(BackgroundReadOutcome.SUCCESS, result.outcome)
        assertEquals(5, result.readTypesCount)
        assertEquals(0, result.totalRecords)
        assertEquals(emptySet<String>(), result.dataOrigins)
    }

    @Test
    fun securityExceptionProducesUserActionRequiredOutcome() = runBlocking {
        val client = FakeHealthConnectClient()
        client.overrides.readRecords = Stub {
            throw SecurityException("Health Connect read permission revoked")
        }

        val reader = BackgroundHealthRecordReader(client = client, clock = clock)
        val result = reader.readConfirmedRecords(window)

        assertEquals(BackgroundReadOutcome.USER_ACTION_REQUIRED, result.outcome)
        assertTrue(result.message.contains("permission", ignoreCase = true))
    }

    @Test
    fun unsupportedOperationProducesUnsupportedOutcome() = runBlocking {
        val client = FakeHealthConnectClient()
        client.overrides.readRecords = Stub {
            throw UnsupportedOperationException("Background reads unsupported on this provider")
        }

        val reader = BackgroundHealthRecordReader(client = client, clock = clock)
        val result = reader.readConfirmedRecords(window)

        assertEquals(BackgroundReadOutcome.UNSUPPORTED, result.outcome)
        assertTrue(result.message.contains("unsupported", ignoreCase = true))
    }

    @Test
    fun ioExceptionProducesRetryableFailureOutcome() = runBlocking {
        val client = FakeHealthConnectClient()
        client.overrides.readRecords = Stub {
            throw IOException("Transient IPC or disk failure")
        }

        val reader = BackgroundHealthRecordReader(client = client, clock = clock)
        val result = reader.readConfirmedRecords(window)

        assertEquals(BackgroundReadOutcome.RETRYABLE_FAILURE, result.outcome)
        assertTrue(result.message.contains("retry", ignoreCase = true) || result.message.contains("transient", ignoreCase = true) || result.message.contains("failed", ignoreCase = true))
    }

    @Test
    fun cancellationIsPropagatedDirectly() {
        val client = FakeHealthConnectClient()
        client.overrides.readRecords = Stub {
            throw CancellationException("Worker was cancelled")
        }

        val reader = BackgroundHealthRecordReader(client = client, clock = clock)
        assertThrows(CancellationException::class.java) {
            runBlocking { reader.readConfirmedRecords(window) }
        }
    }

    @Test
    fun multiplePagesAndOriginsAreCollected() = runBlocking {
        val client = FakeHealthConnectClient()
        client.setPackageName("com.mi.health")
        client.insertRecords(
            listOf(
                steps("2026-08-29T13:00:00Z", "2026-08-29T13:15:00Z"),
                steps("2026-08-29T14:00:00Z", "2026-08-29T14:15:00Z"),
            ),
        )
        client.setPackageName("dev.reva.source")
        client.insertRecords(
            listOf(
                steps("2026-08-29T15:00:00Z", "2026-08-29T15:15:00Z"),
            ),
        )

        val reader = BackgroundHealthRecordReader(client = client, clock = clock, pageSize = 1)
        val result = reader.readConfirmedRecords(window)

        assertEquals(BackgroundReadOutcome.SUCCESS, result.outcome)
        assertEquals(3, result.totalRecords)
        assertEquals(setOf("com.mi.health", "dev.reva.source"), result.dataOrigins)
    }

    private fun createConfirmedRecords(): List<Record> {
        val start = Instant.parse("2026-08-29T13:00:00Z")
        val end = Instant.parse("2026-08-29T13:15:00Z")
        return listOf<Record>(
            steps("2026-08-29T13:00:00Z", "2026-08-29T13:15:00Z"),
            HeartRateRecord(
                startTime = start,
                startZoneOffset = null,
                endTime = end,
                endZoneOffset = null,
                samples = listOf(HeartRateRecord.Sample(start.plusSeconds(30), 72)),
                metadata = Metadata.manualEntry(),
            ),
            DistanceRecord(start, null, end, null, Length.meters(350.0), Metadata.manualEntry()),
            TotalCaloriesBurnedRecord(start, null, end, null, Energy.kilocalories(30.0), Metadata.manualEntry()),
            SleepSessionRecord(start, null, end, null, Metadata.manualEntry()),
        )
    }

    private fun steps(start: String, end: String) = StepsRecord(
        startTime = Instant.parse(start),
        startZoneOffset = null,
        endTime = Instant.parse(end),
        endZoneOffset = null,
        count = 120,
        metadata = Metadata.manualEntry(),
    )
}
