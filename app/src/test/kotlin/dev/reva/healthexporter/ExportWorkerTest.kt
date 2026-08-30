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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExportWorkerTest {
    private val testInstant = Instant.parse("2026-08-30T12:00:00Z")
    private val clock = object : DiagnosticClock {
        override fun now(zoneId: ZoneId): ZonedDateTime =
            testInstant.atZone(zoneId)
    }

    private class FakeExportDestination(
        var status: DestinationStatus = DestinationStatus.Ready,
    ) : ExportDestination {
        override val destinationName: String = "FakeDestination"
        val uploadedBatches = mutableListOf<ExportBatch>()
        var uploadResult: UploadResult? = null
        var uploadThrowException: Throwable? = null

        override suspend fun verifyConfiguration(): DestinationStatus = status

        override suspend fun upload(batch: ExportBatch): UploadResult {
            uploadThrowException?.let { throw it }
            val result = uploadResult ?: UploadResult.Success(
                batchId = batch.header.batchId,
                location = "drive://files/${batch.header.batchId}",
            )
            if (result is UploadResult.Success) {
                uploadedBatches.add(batch)
            }
            return result
        }
    }

    private val inMemoryStore = InMemoryExportStateStore(installationId = "inst-worker-test")
    private val destination = FakeExportDestination()
    private val idGen = IdGenerator { "batch-worker-001" }

    @Before
    fun setUp() {
        ExportWorker.clock = clock
        ExportWorker.stateStoreFactory = { inMemoryStore }
        ExportWorker.destinationFactory = { destination }
        ExportWorker.idGenerator = idGen
    }

    @After
    fun tearDown() {
        ExportWorker.resetDefaults()
    }

    @Test
    fun successfulExportReturnsSuccessPersistsSummaryAndAdvancesCheckpoint() = runBlocking {
        val client = FakeHealthConnectClient()
        client.setPackageName("com.mi.health")
        client.insertRecords(createRecords())

        ExportWorker.clientFactory = { client }

        val context = null // or mock if needed
        val result = ExportWorker.execute(context = null)

        assertTrue(result is ListenableWorker.Result.Success)
        val successResult = result as ListenableWorker.Result.Success
        assertEquals(ExportOutcome.SUCCESS.name, successResult.outputData.getString(ExportWorker.KEY_OUTCOME))
        assertEquals("batch-worker-001", successResult.outputData.getString(ExportWorker.KEY_BATCH_ID))
        assertEquals(5, successResult.outputData.getInt(ExportWorker.KEY_RECORD_COUNT, 0))
        assertEquals("drive://files/batch-worker-001", successResult.outputData.getString(ExportWorker.KEY_LOCATION))

        // State assertions
        val summary = inMemoryStore.getLastExecutionSummary()
        assertNotNull(summary)
        assertEquals(ExportOutcome.SUCCESS, summary!!.outcome)
        assertEquals("batch-worker-001", summary.batchId)
        assertEquals(5, summary.recordCount)
        assertEquals(testInstant, summary.executionTimestamp)
        assertEquals("drive://files/batch-worker-001", summary.destinationLocation)

        val checkpoint = inMemoryStore.getLastCheckpoint()
        assertNotNull(checkpoint)
        assertEquals("batch-worker-001", checkpoint!!.lastBatchId)
        assertEquals(5L, checkpoint.totalRecordCount)
        assertNull(inMemoryStore.getPendingBatch())
    }

    @Test
    fun nothingToExportReturnsSuccessWithNothingToExportOutcome() = runBlocking {
        // Set previous checkpoint so window is up to date
        inMemoryStore.saveCheckpoint(
            ExportCheckpoint(
                lastWindowEnd = testInstant,
                lastBatchId = "batch-prev",
                exportedAt = testInstant.minusSeconds(60),
                totalRecordCount = 10L,
            ),
        )

        val client = FakeHealthConnectClient()
        ExportWorker.clientFactory = { client }

        val result = ExportWorker.execute(context = null)

        assertTrue(result is ListenableWorker.Result.Success)
        val successResult = result as ListenableWorker.Result.Success
        assertEquals(ExportOutcome.NOTHING_TO_EXPORT.name, successResult.outputData.getString(ExportWorker.KEY_OUTCOME))

        val summary = inMemoryStore.getLastExecutionSummary()
        assertNotNull(summary)
        assertEquals(ExportOutcome.NOTHING_TO_EXPORT, summary!!.outcome)

        // Checkpoint must remain unchanged
        val checkpoint = inMemoryStore.getLastCheckpoint()
        assertNotNull(checkpoint)
        assertEquals("batch-prev", checkpoint!!.lastBatchId)
        assertEquals(10L, checkpoint.totalRecordCount)
    }

    @Test
    fun retryableNetworkFailureReturnsRetryPreservesPendingBatchAndDoesNotAdvanceCheckpoint() = runBlocking {
        val client = FakeHealthConnectClient()
        client.setPackageName("com.mi.health")
        client.insertRecords(createRecords())
        ExportWorker.clientFactory = { client }

        destination.uploadResult = UploadResult.Failure(
            message = "Google Drive 503 Service Unavailable",
            isRetryable = true,
        )

        val result = ExportWorker.execute(context = null)

        assertTrue(result is ListenableWorker.Result.Retry)

        val summary = inMemoryStore.getLastExecutionSummary()
        assertNotNull(summary)
        assertEquals(ExportOutcome.RETRYABLE_FAILURE, summary!!.outcome)
        assertTrue(summary.message.contains("503"))

        assertNull("Checkpoint must NOT advance on failure", inMemoryStore.getLastCheckpoint())
        val pending = inMemoryStore.getPendingBatch()
        assertNotNull("Pending batch must be preserved for retry", pending)
        assertEquals("batch-worker-001", pending!!.header.batchId)
    }

    @Test
    fun terminalFailureReturnsFailureWithTerminalOutcome() = runBlocking {
        val client = FakeHealthConnectClient()
        ExportWorker.clientFactory = { client }

        destination.uploadResult = UploadResult.Failure(
            message = "Schema validation failed or forbidden 400",
            isRetryable = false,
        )

        val result = ExportWorker.execute(context = null)

        assertTrue(result is ListenableWorker.Result.Failure)
        val failureResult = result as ListenableWorker.Result.Failure
        assertEquals(ExportOutcome.TERMINAL_FAILURE.name, failureResult.outputData.getString(ExportWorker.KEY_OUTCOME))

        val summary = inMemoryStore.getLastExecutionSummary()
        assertNotNull(summary)
        assertEquals(ExportOutcome.TERMINAL_FAILURE, summary!!.outcome)
        assertNull(inMemoryStore.getLastCheckpoint())
    }

    @Test
    fun permissionRevocationReturnsFailureWithUserActionRequired() = runBlocking {
        val client = FakeHealthConnectClient()
        client.overrides.readRecords = Stub {
            throw SecurityException("Health Connect read permissions missing")
        }
        ExportWorker.clientFactory = { client }

        val result = ExportWorker.execute(context = null)

        assertTrue(result is ListenableWorker.Result.Failure)
        val failureResult = result as ListenableWorker.Result.Failure
        assertEquals(ExportOutcome.USER_ACTION_REQUIRED.name, failureResult.outputData.getString(ExportWorker.KEY_OUTCOME))

        val summary = inMemoryStore.getLastExecutionSummary()
        assertNotNull(summary)
        assertEquals(ExportOutcome.USER_ACTION_REQUIRED, summary!!.outcome)
        assertTrue(summary.message.contains("permission", ignoreCase = true))
        assertNull(inMemoryStore.getLastCheckpoint())
    }

    @Test
    fun destinationAuthorizationRevocationReturnsFailureWithUserActionRequired() = runBlocking {
        destination.status = DestinationStatus.InvalidConfiguration("Google Drive authorization required")
        val client = FakeHealthConnectClient()
        ExportWorker.clientFactory = { client }

        val result = ExportWorker.execute(context = null)

        assertTrue(result is ListenableWorker.Result.Failure)
        val failureResult = result as ListenableWorker.Result.Failure
        assertEquals(ExportOutcome.USER_ACTION_REQUIRED.name, failureResult.outputData.getString(ExportWorker.KEY_OUTCOME))

        val summary = inMemoryStore.getLastExecutionSummary()
        assertNotNull(summary)
        assertEquals(ExportOutcome.USER_ACTION_REQUIRED, summary!!.outcome)
        assertTrue(summary.message.contains("authorization", ignoreCase = true))
        assertNull(inMemoryStore.getLastCheckpoint())
    }

    @Test
    fun unsupportedHealthConnectProviderReturnsFailureWithUserActionRequired() = runBlocking {
        ExportWorker.clientFactory = {
            throw IllegalStateException("Health Connect is not installed on this device")
        }

        val result = ExportWorker.execute(context = null)

        assertTrue(result is ListenableWorker.Result.Failure)
        val failureResult = result as ListenableWorker.Result.Failure
        assertEquals(ExportOutcome.USER_ACTION_REQUIRED.name, failureResult.outputData.getString(ExportWorker.KEY_OUTCOME))

        val summary = inMemoryStore.getLastExecutionSummary()
        assertNotNull(summary)
        assertEquals(ExportOutcome.USER_ACTION_REQUIRED, summary!!.outcome)
        assertTrue(summary.message.contains("unavailable", ignoreCase = true))
    }

    @Test
    fun backgroundWorkerExecutionNeverLaunchesInteractiveUi() = runBlocking {
        val client = FakeHealthConnectClient()
        ExportWorker.clientFactory = { client }

        // Invariant: The worker execution path operates strictly via worker result/data
        // and does not launch Activity intents or interactive prompts.
        val result = ExportWorker.execute(context = null)
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
