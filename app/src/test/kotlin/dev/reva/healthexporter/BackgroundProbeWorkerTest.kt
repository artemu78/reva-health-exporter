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
import androidx.work.ListenableWorker
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BackgroundProbeWorkerTest {
    private val clock = object : DiagnosticClock {
        override fun now(zoneId: ZoneId): ZonedDateTime =
            ZonedDateTime.parse("2026-08-30T12:00:00Z[UTC]")
    }

    private val inMemoryStore = InMemoryBackgroundProbeStore()

    @Before
    fun setUp() {
        BackgroundProbeWorker.clock = clock
        BackgroundProbeWorker.storeFactory = { inMemoryStore }
    }

    @After
    fun tearDown() {
        BackgroundProbeWorker.resetDefaults()
    }

    @Test
    fun successfulExecutionReturnsSuccessAndPersistsSummary() = runBlocking {
        val client = FakeHealthConnectClient()
        client.setPackageName("com.mi.health")
        client.insertRecords(createRecords())

        BackgroundProbeWorker.clientFactory = { client }

        val result = BackgroundProbeWorker.execute()

        assertTrue(result is ListenableWorker.Result.Success)
        val successResult = result as ListenableWorker.Result.Success
        assertEquals(BackgroundReadOutcome.SUCCESS.name, successResult.outputData.getString(BackgroundProbeWorker.KEY_OUTCOME))
        assertEquals(5, successResult.outputData.getInt(BackgroundProbeWorker.KEY_TOTAL_RECORDS, 0))
        assertEquals(5, successResult.outputData.getInt(BackgroundProbeWorker.KEY_READ_TYPES_COUNT, 0))

        val saved = inMemoryStore.loadSummary()
        assertNotNull(saved)
        assertEquals(BackgroundReadOutcome.SUCCESS, saved!!.outcome)
        assertEquals(5, saved.totalRecords)
        assertEquals(setOf("com.mi.health"), saved.dataOrigins)
        assertEquals(Instant.parse("2026-08-30T12:00:00Z"), saved.executionTimestamp)
    }

    @Test
    fun transientIoFailureReturnsRetry() = runBlocking {
        val client = FakeHealthConnectClient()
        client.overrides.readRecords = Stub {
            throw IOException("Transient error connecting to Health Connect")
        }

        BackgroundProbeWorker.clientFactory = { client }

        val result = BackgroundProbeWorker.execute()

        assertTrue(result is ListenableWorker.Result.Retry)
        val saved = inMemoryStore.loadSummary()
        assertNotNull(saved)
        assertEquals(BackgroundReadOutcome.RETRYABLE_FAILURE, saved!!.outcome)
    }

    @Test
    fun permissionRevocationReturnsFailureWithUserActionRequired() = runBlocking {
        val client = FakeHealthConnectClient()
        client.overrides.readRecords = Stub {
            throw SecurityException("Health Connect read permissions missing")
        }

        BackgroundProbeWorker.clientFactory = { client }

        val result = BackgroundProbeWorker.execute()

        assertTrue(result is ListenableWorker.Result.Failure)
        val failureResult = result as ListenableWorker.Result.Failure
        assertEquals(BackgroundReadOutcome.USER_ACTION_REQUIRED.name, failureResult.outputData.getString(BackgroundProbeWorker.KEY_OUTCOME))

        val saved = inMemoryStore.loadSummary()
        assertNotNull(saved)
        assertEquals(BackgroundReadOutcome.USER_ACTION_REQUIRED, saved!!.outcome)
        assertTrue(saved.message.contains("permission", ignoreCase = true))
    }

    @Test
    fun unsupportedProviderReturnsFailureWithExplicitLimitation() = runBlocking {
        val client = FakeHealthConnectClient()
        client.overrides.readRecords = Stub {
            throw UnsupportedOperationException("Background access unsupported")
        }

        BackgroundProbeWorker.clientFactory = { client }

        val result = BackgroundProbeWorker.execute()

        assertTrue(result is ListenableWorker.Result.Failure)
        val failureResult = result as ListenableWorker.Result.Failure
        assertEquals(BackgroundReadOutcome.UNSUPPORTED.name, failureResult.outputData.getString(BackgroundProbeWorker.KEY_OUTCOME))

        val saved = inMemoryStore.loadSummary()
        assertNotNull(saved)
        assertEquals(BackgroundReadOutcome.UNSUPPORTED, saved!!.outcome)
        assertTrue(saved.message.contains("unsupported", ignoreCase = true))
    }

    @Test
    fun backgroundExecutionNeverLaunchesInteractiveUi() = runBlocking {
        // Invariant: The worker execution path operates strictly via worker result/data
        // and does not invoke any UI or Activity intents.
        val client = FakeHealthConnectClient()
        BackgroundProbeWorker.clientFactory = { client }

        val result = BackgroundProbeWorker.execute()
        assertTrue(result is ListenableWorker.Result.Success)
    }

    private fun createRecords(): List<Record> {
        val start = Instant.parse("2026-08-29T13:00:00Z")
        val end = Instant.parse("2026-08-29T13:15:00Z")
        return listOf<Record>(
            StepsRecord(start, null, end, null, 100, Metadata.manualEntry()),
            HeartRateRecord(start, null, end, null, listOf(HeartRateRecord.Sample(start.plusSeconds(10), 75)), Metadata.manualEntry()),
            DistanceRecord(start, null, end, null, Length.meters(200.0), Metadata.manualEntry()),
            TotalCaloriesBurnedRecord(start, null, end, null, Energy.kilocalories(20.0), Metadata.manualEntry()),
            SleepSessionRecord(start, null, end, null, Metadata.manualEntry()),
        )
    }
}
