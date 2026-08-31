package dev.reva.healthexporter

import android.content.Context
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.testing.FakeHealthConnectClient
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestDriver
import androidx.work.testing.WorkManagerTestInitHelper
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkManagerExportIntegrationTest {
    private val testInstant = Instant.parse("2026-08-30T12:00:00Z")
    private val clock = object : DiagnosticClock {
        override fun now(zoneId: ZoneId): ZonedDateTime =
            testInstant.atZone(zoneId)
    }

    private class FakeExportDestination : ExportDestination {
        override val destinationName: String = "FakeDestination"
        val uploadedBatches = mutableListOf<ExportBatch>()

        override suspend fun verifyConfiguration(): DestinationStatus = DestinationStatus.Ready

        override suspend fun upload(batch: ExportBatch): UploadResult {
            uploadedBatches.add(batch)
            return UploadResult.Success(batchId = batch.header.batchId, location = "fake://${batch.header.batchId}")
        }
    }

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var testDriver: TestDriver
    private lateinit var stateStore: SharedPreferencesExportStateStore
    private val destination = FakeExportDestination()
    private val client = FakeHealthConnectClient()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        testDriver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))

        stateStore = SharedPreferencesExportStateStore(context)
        stateStore.clear()

        client.setPackageName("com.mi.health")
        runBlocking {
            client.insertRecords(createRecords())
        }

        ExportWorker.clock = clock
        ExportWorker.stateStoreFactory = { stateStore }
        ExportWorker.destinationFactory = { destination }
        ExportWorker.clientFactory = { client }
    }

    @After
    fun tearDown() {
        ExportWorker.resetDefaults()
        stateStore.clear()
        workManager.cancelAllWork()
    }

    @Test
    fun unique_periodic_work_prevents_duplicate_schedules() {
        ExportScheduler.schedulePeriodicExport(workManager, Duration.ofHours(1), ExistingPeriodicWorkPolicy.KEEP)

        val workInfos1 = workManager.getWorkInfosForUniqueWork(ExportScheduler.PERIODIC_WORK_NAME).get()
        assertEquals(1, workInfos1.size)
        val originalId = workInfos1[0].id

        // Schedule again with KEEP policy
        ExportScheduler.schedulePeriodicExport(workManager, Duration.ofHours(1), ExistingPeriodicWorkPolicy.KEEP)

        val workInfos2 = workManager.getWorkInfosForUniqueWork(ExportScheduler.PERIODIC_WORK_NAME).get()
        assertEquals(1, workInfos2.size)
        assertEquals(originalId, workInfos2[0].id)
    }

    @Test
    fun periodic_work_interval_triggering_using_test_helpers_without_sleeping() {
        val request = ExportScheduler.buildPeriodicWorkRequest(interval = Duration.ofHours(1))
        workManager.enqueueUniquePeriodicWork(
            ExportScheduler.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.REPLACE,
            request,
        )

        testDriver.setAllConstraintsMet(request.id)
        testDriver.setPeriodDelayMet(request.id)

        val workInfo = checkNotNull(workManager.getWorkInfoById(request.id).get())
        assertEquals(WorkInfo.State.ENQUEUED, workInfo.state)

        val summary = stateStore.getLastExecutionSummary()
        assertNotNull(summary)
        assertEquals(ExportOutcome.SUCCESS, summary!!.outcome)
        assertEquals(5, summary.recordCount)

        val checkpoint = stateStore.getLastCheckpoint()
        assertNotNull(checkpoint)
        assertEquals(5L, checkpoint!!.totalRecordCount)
    }

    @Test
    fun cancellation_stops_periodic_export() {
        ExportScheduler.schedulePeriodicExport(workManager, Duration.ofHours(1))

        var workInfos = workManager.getWorkInfosForUniqueWork(ExportScheduler.PERIODIC_WORK_NAME).get()
        assertEquals(1, workInfos.size)
        assertEquals(WorkInfo.State.ENQUEUED, workInfos[0].state)

        ExportScheduler.cancelPeriodicExport(workManager)

        workInfos = workManager.getWorkInfosForUniqueWork(ExportScheduler.PERIODIC_WORK_NAME).get()
        assertEquals(1, workInfos.size)
        assertEquals(WorkInfo.State.CANCELLED, workInfos[0].state)
    }

    @Test
    fun offline_to_online_recovery_executes_after_connectivity_returns() {
        val request = ExportScheduler.buildOneTimeWorkRequest()
        workManager.enqueueUniqueWork(
            ExportScheduler.ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )

        // Work is blocked until constraints are met
        val initialInfo = checkNotNull(workManager.getWorkInfoById(request.id).get())
        assertEquals(WorkInfo.State.ENQUEUED, initialInfo.state)

        // Connectivity becomes available
        testDriver.setAllConstraintsMet(request.id)

        val completedInfo = checkNotNull(workManager.getWorkInfoById(request.id).get())
        assertEquals(WorkInfo.State.SUCCEEDED, completedInfo.state)

        val summary = stateStore.getLastExecutionSummary()
        assertNotNull(summary)
        assertEquals(ExportOutcome.SUCCESS, summary!!.outcome)
    }

    @Test
    fun export_now_action_uses_one_time_work_through_the_same_pipeline() {
        val workId = ExportScheduler.triggerImmediateExport(workManager, ExistingWorkPolicy.REPLACE)

        testDriver.setAllConstraintsMet(workId)

        val workInfo = checkNotNull(workManager.getWorkInfoById(workId).get())
        assertEquals(WorkInfo.State.SUCCEEDED, workInfo.state)

        val summary = stateStore.getLastExecutionSummary()
        assertNotNull(summary)
        assertEquals(ExportOutcome.SUCCESS, summary!!.outcome)
        assertEquals(5, summary.recordCount)

        val checkpoint = stateStore.getLastCheckpoint()
        assertNotNull(checkpoint)
        assertEquals(5L, checkpoint!!.totalRecordCount)
    }

    @Test
    fun production_worker_destination_uses_background_drive_authorization_token() = runBlocking {
        ExportWorker.destinationFactory = null
        ExportWorker.driveTokenProviderFactory = { { "synthetic-drive-token" } }
        val requests = mutableListOf<HttpRequest>()
        ExportWorker.driveTransportFactory = {
            HttpTransport { request ->
                requests += request
                HttpResponse(statusCode = 200, body = "{\"files\":[]}".toByteArray())
            }
        }
        stateStore.saveCheckpoint(
            ExportCheckpoint(
                lastWindowEnd = testInstant,
                lastBatchId = "previous-batch",
                exportedAt = testInstant.minusSeconds(60),
                totalRecordCount = 5,
            ),
        )

        val result = ExportWorker.execute(context)

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(requests.isNotEmpty())
        assertEquals("Bearer synthetic-drive-token", requests.first().headers["Authorization"])
    }

    @Test
    fun concurrency_periodic_work_and_export_now_run_safely_without_corruption() = runBlocking {
        // Run concurrent executions of ExportWorker.execute
        val results = coroutineScope {
            val job1 = async { ExportWorker.execute(context) }
            val job2 = async { ExportWorker.execute(context) }
            listOf(job1.await(), job2.await())
        }

        // Both executions finish safely
        assertTrue(results.all { it is ListenableWorker.Result.Success })

        // Exactly one batch should have uploaded the 5 records, the other handles NothingToExport
        assertEquals(1, destination.uploadedBatches.size)
        val checkpoint = stateStore.getLastCheckpoint()
        assertNotNull(checkpoint)
        assertEquals(5L, checkpoint!!.totalRecordCount)
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
