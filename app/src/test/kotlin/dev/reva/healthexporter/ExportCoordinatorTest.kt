package dev.reva.healthexporter

import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportCoordinatorTest {

    private class TestClock(var currentInstant: Instant) : DiagnosticClock {
        override fun now(zoneId: ZoneId): ZonedDateTime =
            currentInstant.atZone(zoneId)
    }

    private class TestIdGenerator : IdGenerator {
        var nextId: String = "batch-default-001"
        override fun generateId(): String = nextId
    }

    private class FakeExportDestination(
        var status: DestinationStatus = DestinationStatus.Ready,
    ) : ExportDestination {
        override val destinationName: String = "FakeDestination"
        val uploadedBatches = mutableListOf<ExportBatch>()
        var uploadResult: UploadResult? = null
        var uploadThrowException: Throwable? = null
        var verificationException: Exception? = null

        override suspend fun verifyConfiguration(): DestinationStatus {
            verificationException?.let { throw it }
            return status
        }

        override suspend fun upload(batch: ExportBatch): UploadResult {
            uploadThrowException?.let { throw it }
            val result = uploadResult ?: UploadResult.Success(batchId = batch.header.batchId, location = "fake://${batch.header.batchId}")
            if (result is UploadResult.Success) {
                uploadedBatches.add(batch)
            }
            return result
        }
    }

    private class FakeRecordReader : HealthExportRecordReader {
        var recordsToReturn = mutableListOf<CanonicalRecord>()
        var exceptionToThrow: Throwable? = null
        var lastQueriedWindow: TimeWindow? = null

        override suspend fun readRecords(timeWindow: TimeWindow): List<CanonicalRecord> {
            lastQueriedWindow = timeWindow
            exceptionToThrow?.let { throw it }
            return recordsToReturn
        }
    }

    private val clock = TestClock(Instant.parse("2026-08-30T12:00:00Z"))
    private val idGen = TestIdGenerator()
    private val stateStore = InMemoryExportStateStore(installationId = "inst-test-01")
    private val destination = FakeExportDestination()
    private val reader = FakeRecordReader()

    private fun createCoordinator(
        initialLookback: Duration = Duration.ofDays(1),
        maxBatchDuration: Duration? = Duration.ofDays(1),
        exportStateStore: ExportStateStore = stateStore,
        zoneId: ZoneId = ZoneOffset.UTC,
    ): ExportCoordinator = ExportCoordinator(
        stateStore = exportStateStore,
        recordReader = reader,
        destination = destination,
        clock = clock,
        zoneId = zoneId,
        idGenerator = idGen,
        config = ExportCoordinatorConfig(
            initialLookbackPeriod = initialLookback,
            maxBatchDuration = maxBatchDuration,
        ),
    )

    private fun expectedDailyBatchId(date: String = "2026-08-29", zoneId: ZoneId = ZoneOffset.UTC): String =
        dailySnapshotKey(destination.destinationName, null, zoneId, LocalDate.parse(date)).identity

    private fun createSampleRecord(
        id: String,
        startTime: Instant,
        type: String = "steps",
    ): CanonicalRecord = CanonicalStepsRecord(
        startTime = startTime,
        startZoneOffset = ZoneOffset.UTC,
        endTime = startTime.plusSeconds(900),
        endZoneOffset = ZoneOffset.UTC,
        metadata = RecordMetadata(recordId = id, origin = "com.mi.health"),
        count = 1000,
    )

    private fun createSleepSnapshot(
        id: String,
        startTime: Instant,
        endTime: Instant,
    ): CanonicalSleepSessionRecord = CanonicalSleepSessionRecord(
        startTime = startTime,
        startZoneOffset = ZoneOffset.UTC,
        endTime = endTime,
        endZoneOffset = ZoneOffset.UTC,
        metadata = RecordMetadata(
            recordId = id,
            origin = "com.xiaomi.wearable",
        ),
        stages = listOf(
            SleepStage(
                startTime = startTime,
                endTime = endTime,
                stage = 4,
            ),
        ),
    )

    @Test
    fun initialExportCreatesBatchPersistsPendingUploadsAndAdvancesCheckpoint() = runBlocking {
        val coordinator = createCoordinator()
        val expectedBatchId = expectedDailyBatchId("2026-08-29")

        val record1 = createSampleRecord("r-1", Instant.parse("2026-08-29T14:00:00Z"))
        val record2 = createSampleRecord("r-2", Instant.parse("2026-08-29T16:00:00Z"))
        reader.recordsToReturn = mutableListOf(record1, record2)

        val result = coordinator.export()
        assertTrue("Result should be success: $result", result is ExportCycleResult.Success)
        val success = result as ExportCycleResult.Success
        assertFalse("Initial export should not be a retry", success.isRetry)
        assertEquals(expectedBatchId, success.batch.header.batchId)
        assertEquals(2, success.batch.header.recordCount)

        // Verify destination received the batch
        assertEquals(1, destination.uploadedBatches.size)
        assertEquals(expectedBatchId, destination.uploadedBatches.first().header.batchId)

        // Verify checkpoint advanced
        val checkpoint = stateStore.getLastCheckpoint()
        assertNotNull(checkpoint)
        assertEquals(expectedBatchId, checkpoint!!.lastBatchId)
        assertEquals(Instant.parse("2026-08-30T00:00:00Z"), checkpoint.lastWindowEnd)
        assertEquals(2L, checkpoint.totalRecordCount)

        // Verify pending batch was cleared
        assertNull(stateStore.getPendingBatch())
    }

    @Test
    fun incrementalExportStartsFromPreviousCheckpointEnd() = runBlocking {
        val coordinator = createCoordinator()

        // 1. Initial export
        val expectedBatch1 = expectedDailyBatchId("2026-08-29")
        reader.recordsToReturn = mutableListOf(createSampleRecord("r-1", Instant.parse("2026-08-29T14:00:00Z")))
        coordinator.export()

        val cp1 = stateStore.getLastCheckpoint()
        assertEquals(Instant.parse("2026-08-30T00:00:00Z"), cp1!!.lastWindowEnd)
        assertEquals(expectedBatch1, cp1.lastBatchId)

        // 2. Advance time by 6 hours
        clock.currentInstant = Instant.parse("2026-08-30T18:00:00Z")
        val expectedBatch2 = expectedDailyBatchId("2026-08-30")
        reader.recordsToReturn = mutableListOf(createSampleRecord("r-2", Instant.parse("2026-08-30T14:00:00Z")))

        val result2 = coordinator.export()
        assertTrue(result2 is ExportCycleResult.Success)
        val success2 = result2 as ExportCycleResult.Success
        assertEquals(expectedBatch2, success2.batch.header.batchId)

        // Verify query window started at previous checkpoint end
        assertEquals(Instant.parse("2026-08-30T00:00:00Z"), reader.lastQueriedWindow?.startInclusive)
        assertEquals(Instant.parse("2026-08-31T00:00:00Z"), reader.lastQueriedWindow?.endExclusive)

        // Verify total record count accumulated
        val cp2 = stateStore.getLastCheckpoint()
        assertEquals(2L, cp2!!.totalRecordCount)
        assertEquals(expectedBatch2, cp2.lastBatchId)
    }

    @Test
    fun retryReusesPendingBatchIdentityAndContentsWithoutQueryingHealthConnect() = runBlocking {
        val coordinator = createCoordinator()
        val expectedBatchId = expectedDailyBatchId("2026-08-29")

        val originalRecord = createSampleRecord("r-orig", Instant.parse("2026-08-29T14:00:00Z"))
        reader.recordsToReturn = mutableListOf(originalRecord)

        // 1. Simulate destination upload failure
        destination.uploadResult = UploadResult.Failure(message = "Simulated network timeout", isRetryable = true)

        val failResult = coordinator.export()
        assertTrue(failResult is ExportCycleResult.RetryableFailure)

        // Invariants: Checkpoint NOT advanced, pending batch REMAINS in store
        assertNull("Checkpoint must not advance on failure", stateStore.getLastCheckpoint())
        val pendingBatch = stateStore.getPendingBatch()
        assertNotNull("Pending batch must be persisted", pendingBatch)
        assertEquals(expectedBatchId, pendingBatch!!.header.batchId)
        assertEquals(1, pendingBatch.records.size)

        // 2. Modify reader and idGen to prove they are NOT used during retry
        idGen.nextId = "batch-DIFFERENT-002"
        reader.recordsToReturn = mutableListOf(createSampleRecord("r-NEW", Instant.parse("2026-08-29T15:00:00Z")))
        reader.exceptionToThrow = IllegalStateException("Reader should not be invoked during retry")
        destination.uploadResult = null // Will succeed

        // 3. Perform retry
        val retryResult = coordinator.export()
        assertTrue(retryResult is ExportCycleResult.Success)
        val success = retryResult as ExportCycleResult.Success
        assertTrue("Should be marked as retry", success.isRetry)
        assertEquals(expectedBatchId, success.batch.header.batchId)
        assertEquals("r-orig", success.batch.records.first().metadata.recordId)

        // Verify checkpoint advanced with original batchId
        val cp = stateStore.getLastCheckpoint()
        assertNotNull(cp)
        assertEquals(expectedBatchId, cp!!.lastBatchId)
        assertNull(stateStore.getPendingBatch())
    }

    @Test
    fun restartRecoveryResumesPendingBatch() = runBlocking {
        // Prepare pending batch in state store to simulate app kill / restart
        val window = TimeWindow(
            startInclusive = Instant.parse("2026-08-29T00:00:00Z"),
            endExclusive = Instant.parse("2026-08-30T00:00:00Z"),
        )
        val record = createSampleRecord("r-restart", Instant.parse("2026-08-29T10:00:00Z"))
        val pendingBatch = ExportBatch(
            header = BatchHeader(
                schemaVersion = 1,
                installationId = "inst-test-01",
                batchId = "batch-interrupted-001",
                createdAt = Instant.parse("2026-08-30T10:00:00Z"),
                timeWindow = window,
                recordCount = 1,
                recordTypes = listOf("steps"),
            ),
            records = listOf(record),
        )
        stateStore.savePendingBatch(pendingBatch)

        // Create new coordinator instance (simulating restart)
        val coordinator = createCoordinator()
        val result = coordinator.export()

        assertTrue(result is ExportCycleResult.Success)
        val success = result as ExportCycleResult.Success
        assertTrue(success.isRetry)
        assertEquals("batch-interrupted-001", success.batch.header.batchId)

        assertEquals("batch-interrupted-001", stateStore.getLastCheckpoint()?.lastBatchId)
        assertNull(stateStore.getPendingBatch())
    }

    @Test
    fun failureBeforeBatchPersistenceDoesNotPersistBatchOrAdvanceCheckpoint() = runBlocking {
        val coordinator = createCoordinator()
        reader.exceptionToThrow = IOException("Health Connect service error")

        val result = coordinator.export()
        assertTrue(result is ExportCycleResult.RetryableFailure)
        assertNull(stateStore.getPendingBatch())
        assertNull(stateStore.getLastCheckpoint())
    }

    @Test
    fun failureDuringBatchPersistenceDoesNotAttemptUploadOrAdvanceCheckpoint() = runBlocking {
        val coordinator = createCoordinator()
        stateStore.failOnSavePendingBatch = IllegalStateException("Disk full")

        val result = coordinator.export()
        assertTrue(result is ExportCycleResult.RetryableFailure)
        assertTrue(destination.uploadedBatches.isEmpty())
        assertNull(stateStore.getLastCheckpoint())
    }

    @Test
    fun failureDuringCheckpointSaveRetainsPendingBatchForNextRun() = runBlocking {
        val coordinator = createCoordinator()
        val expectedBatchId = expectedDailyBatchId("2026-08-29")
        reader.recordsToReturn = mutableListOf(createSampleRecord("r-1", Instant.parse("2026-08-29T14:00:00Z")))
        stateStore.failOnSaveCheckpoint = IllegalStateException("Checkpoint disk error")

        val result = coordinator.export()
        assertTrue(result is ExportCycleResult.RetryableFailure)

        // Upload succeeded, but checkpoint failed: pending batch must remain for retry
        assertNotNull(stateStore.getPendingBatch())
        assertEquals(expectedBatchId, stateStore.getPendingBatch()?.header?.batchId)
        assertNull(stateStore.getLastCheckpoint())

        // Next run succeeds when checkpoint failure is resolved
        stateStore.failOnSaveCheckpoint = null
        val result2 = coordinator.export()
        assertTrue(result2 is ExportCycleResult.Success)
        assertEquals(expectedBatchId, stateStore.getLastCheckpoint()?.lastBatchId)
        assertNull(stateStore.getPendingBatch())
    }

    @Test
    fun invalidDestinationConfigurationReturnsTerminalFailure() = runBlocking {
        destination.status = DestinationStatus.InvalidConfiguration("Target folder does not exist and cannot be created")
        val coordinator = createCoordinator()

        val result = coordinator.export()
        assertTrue(result is ExportCycleResult.TerminalFailure)
        val term = result as ExportCycleResult.TerminalFailure
        assertTrue(term.userActionRequired)
        assertNull(stateStore.getPendingBatch())
        assertNull(stateStore.getLastCheckpoint())
    }

    @Test
    fun concurrentExportTriggersAreSynchronizedWithoutDuplicateBatches() = runBlocking {
        val coordinator = createCoordinator()
        stateStore.saveCheckpoint(
            ExportCheckpoint(
                lastWindowEnd = Instant.parse("2026-08-30T00:00:00Z"),
                lastBatchId = "batch-prev",
                exportedAt = Instant.parse("2026-08-30T00:01:00Z"),
                totalRecordCount = 0L,
            ),
        )
        reader.recordsToReturn = mutableListOf(createSampleRecord("r-concurrent", Instant.parse("2026-08-30T14:00:00Z")))

        // Trigger two concurrent exports
        val results = coroutineScope {
            val d1 = async { coordinator.export() }
            val d2 = async { coordinator.export() }
            listOf(d1.await(), d2.await())
        }

        // Exactly one export should create and upload a batch; the second should see nothing to export (or empty window)
        val successCount = results.count { it is ExportCycleResult.Success }
        val nothingCount = results.count { it is ExportCycleResult.NothingToExport }

        assertEquals(1, successCount)
        assertEquals(1, nothingCount)
        assertEquals(1, destination.uploadedBatches.size)
    }

    @Test
    fun duplicateRecordsFromReaderAreDeduplicatedInBatch() = runBlocking {
        val coordinator = createCoordinator()
        val rec1 = createSampleRecord("dup-01", Instant.parse("2026-08-29T14:00:00Z"))
        val rec2 = createSampleRecord("dup-01", Instant.parse("2026-08-29T14:00:00Z"))
        reader.recordsToReturn = mutableListOf(rec1, rec2)

        val result = coordinator.export()
        assertTrue(result is ExportCycleResult.Success)
        val batch = (result as ExportCycleResult.Success).batch
        assertEquals(1, batch.header.recordCount)
        assertEquals(1, batch.records.size)
    }

    @Test
    fun progressiveXiaomiSleepSnapshotsProduceOneLatestSessionInExportedJson() = runBlocking {
        clock.currentInstant = Instant.parse("2026-09-04T12:00:00Z")
        val start = Instant.parse("2026-09-03T23:00:00Z")
        reader.recordsToReturn = listOf(
            "2026-09-04T01:38:00Z",
            "2026-09-04T02:22:00Z",
            "2026-09-04T05:06:00Z",
            "2026-09-04T06:54:00Z",
        ).mapIndexed { index, end ->
            createSleepSnapshot(
                id = "xiaomi-sleep-revision-$index",
                startTime = start,
                endTime = Instant.parse(end),
            )
        }.toMutableList()

        val result = createCoordinator().export() as ExportCycleResult.Success
        val exported = ExportBatchSerializer().parseJson(
            ExportBatchSerializer().serializeToJson(result.batch),
        )

        assertEquals(1, exported.header.recordCount)
        assertEquals(1, exported.records.size)
        assertEquals(
            Instant.parse("2026-09-04T06:54:00Z"),
            exported.records.single().endTime,
        )
    }

    @Test
    fun emptyRecordsProduceValidEmptyBatchAndAdvanceCheckpoint() = runBlocking {
        val coordinator = createCoordinator()
        val expectedBatchId = expectedDailyBatchId("2026-08-29")
        reader.recordsToReturn = mutableListOf()

        val result = coordinator.export()
        assertTrue(result is ExportCycleResult.Success)
        val success = result as ExportCycleResult.Success
        assertEquals(expectedBatchId, success.batch.header.batchId)
        assertEquals(0, success.batch.header.recordCount)
        assertTrue(success.batch.header.recordTypes.isEmpty())
        assertTrue(success.batch.records.isEmpty())

        val cp = stateStore.getLastCheckpoint()
        assertNotNull(cp)
        assertEquals(expectedBatchId, cp!!.lastBatchId)
        assertEquals(0L, cp.totalRecordCount)
        assertNull(stateStore.getPendingBatch())
    }

    @Test
    fun maxBatchDurationClampsWindowWhenBacklogIsLarge() = runBlocking {
        // Lookback is 7 days, max batch duration is 1 day
        val coordinator = createCoordinator(
            initialLookback = Duration.ofDays(7),
            maxBatchDuration = Duration.ofDays(1),
        )
        val expectedBatchId = expectedDailyBatchId("2026-08-23")
        reader.recordsToReturn = mutableListOf()

        val result = coordinator.export()
        assertTrue(result is ExportCycleResult.Success)
        val success = result as ExportCycleResult.Success

        // Window should be clamped to 1 day: [2026-08-23T00:00:00Z, 2026-08-24T00:00:00Z)
        assertEquals(Instant.parse("2026-08-23T00:00:00Z"), success.batch.header.timeWindow.startInclusive)
        assertEquals(Instant.parse("2026-08-24T00:00:00Z"), success.batch.header.timeWindow.endExclusive)
        assertEquals(expectedBatchId, success.batch.header.batchId)

        val cp = stateStore.getLastCheckpoint()
        assertEquals(Instant.parse("2026-08-24T00:00:00Z"), cp!!.lastWindowEnd)
    }

    @Test
    fun multipleSequentialExportsAdvanceCheckpointIncrementally() = runBlocking {
        val coordinator = createCoordinator(
            initialLookback = Duration.ofDays(3),
            maxBatchDuration = Duration.ofDays(1),
        )

        // Day 1: 2026-08-27
        reader.recordsToReturn = mutableListOf(createSampleRecord("r-d1", Instant.parse("2026-08-27T14:00:00Z")))
        val r1 = coordinator.export()
        assertTrue(r1 is ExportCycleResult.Success)
        assertEquals(Instant.parse("2026-08-28T00:00:00Z"), stateStore.getLastCheckpoint()?.lastWindowEnd)
        assertEquals(1L, stateStore.getLastCheckpoint()?.totalRecordCount)

        // Day 2: 2026-08-28
        reader.recordsToReturn = mutableListOf(createSampleRecord("r-d2", Instant.parse("2026-08-28T14:00:00Z")))
        val r2 = coordinator.export()
        assertTrue(r2 is ExportCycleResult.Success)
        assertEquals(Instant.parse("2026-08-29T00:00:00Z"), stateStore.getLastCheckpoint()?.lastWindowEnd)
        assertEquals(2L, stateStore.getLastCheckpoint()?.totalRecordCount)

        // Day 3: 2026-08-29
        reader.recordsToReturn = mutableListOf(createSampleRecord("r-d3", Instant.parse("2026-08-29T14:00:00Z")))
        val r3 = coordinator.export()
        assertTrue(r3 is ExportCycleResult.Success)
        assertEquals(Instant.parse("2026-08-30T00:00:00Z"), stateStore.getLastCheckpoint()?.lastWindowEnd)
        assertEquals(3L, stateStore.getLastCheckpoint()?.totalRecordCount)

        // Day 4: 2026-08-30 (today)
        reader.recordsToReturn = mutableListOf(createSampleRecord("r-d4", Instant.parse("2026-08-30T14:00:00Z")))
        val r4 = coordinator.export()
        assertTrue(r4 is ExportCycleResult.Success)
        assertEquals(Instant.parse("2026-08-31T00:00:00Z"), stateStore.getLastCheckpoint()?.lastWindowEnd)
        assertEquals(4L, stateStore.getLastCheckpoint()?.totalRecordCount)

        // Up to date (now = 2026-08-30T12:00:00Z, checkpoint = 2026-08-31T00:00:00Z)
        val r5 = coordinator.export()
        assertTrue(r5 is ExportCycleResult.NothingToExport)
    }

    @Test
    fun destinationUploadThrowsUnexpectedExceptionHandledGracefully() = runBlocking {
        val coordinator = createCoordinator()
        val expectedBatchId = expectedDailyBatchId("2026-08-29")
        reader.recordsToReturn = mutableListOf(createSampleRecord("r-1", Instant.parse("2026-08-29T14:00:00Z")))
        destination.uploadThrowException = RuntimeException("Unexpected socket crash")

        val result = coordinator.export()
        assertTrue(result is ExportCycleResult.RetryableFailure)
        val failure = result as ExportCycleResult.RetryableFailure
        assertTrue(failure.message.contains("Unexpected socket crash"))

        // Invariant: pending batch is preserved, checkpoint is not advanced
        assertNotNull(stateStore.getPendingBatch())
        assertEquals(expectedBatchId, stateStore.getPendingBatch()?.header?.batchId)
        assertNull(stateStore.getLastCheckpoint())
    }

    @Test
    fun unavailableDestinationIsRetryableWithoutReadingOrChangingState() = runBlocking {
        destination.status = DestinationStatus.Unavailable("Offline")
        reader.exceptionToThrow = AssertionError("reader must not run")

        val result = createCoordinator().export()

        assertTrue(result is ExportCycleResult.RetryableFailure)
        assertEquals("Offline", (result as ExportCycleResult.RetryableFailure).message)
        assertNull(stateStore.getPendingBatch())
        assertNull(stateStore.getLastCheckpoint())
    }

    @Test
    fun revokedHealthPermissionRequiresUserActionWithoutChangingState() = runBlocking {
        reader.exceptionToThrow = SecurityException()

        val result = createCoordinator().export()

        assertTrue(result is ExportCycleResult.TerminalFailure)
        val failure = result as ExportCycleResult.TerminalFailure
        assertTrue(failure.userActionRequired)
        assertTrue(failure.message.contains("permission revoked"))
        assertNull(stateStore.getPendingBatch())
        assertNull(stateStore.getLastCheckpoint())
    }

    @Test
    fun nonRetryableUploadFailurePreservesPendingBatchAndCheckpoint() = runBlocking {
        val expectedBatchId = expectedDailyBatchId("2026-08-29")
        destination.uploadResult = UploadResult.Failure("Drive authorization revoked", isRetryable = false)

        val result = createCoordinator().export()

        assertTrue(result is ExportCycleResult.TerminalFailure)
        assertEquals(expectedBatchId, (result as ExportCycleResult.TerminalFailure).batch?.header?.batchId)
        assertEquals(expectedBatchId, stateStore.getPendingBatch()?.header?.batchId)
        assertNull(stateStore.getLastCheckpoint())
    }

    @Test
    fun corruptPendingStateFailsVisiblyWithoutReadingOrChangingCheckpoint() = runBlocking {
        stateStore.failOnGetPendingBatch = IllegalStateException("corrupt pending batch")
        reader.exceptionToThrow = AssertionError("reader must not run")

        val result = createCoordinator().export()

        assertTrue(result is ExportCycleResult.TerminalFailure)
        assertTrue((result as ExportCycleResult.TerminalFailure).message.contains("corrupt pending batch"))
        assertNull(stateStore.getLastCheckpoint())
    }

    @Test
    fun unlimitedBatchDurationExportsFullBacklogThroughCurrentDayBound() = runBlocking {
        val result = createCoordinator(
            initialLookback = Duration.ofDays(3),
            maxBatchDuration = null,
        ).export()

        assertTrue(result is ExportCycleResult.Success)
        assertEquals(
            Instant.parse("2026-08-31T00:00:00Z"),
            (result as ExportCycleResult.Success).batch.header.timeWindow.endExclusive,
        )
    }

    @Test
    fun checkpointReadFailureStopsExportWithoutOverwritingExistingProgress() = runBlocking {
        val checkpoint = ExportCheckpoint(
            lastWindowEnd = clock.currentInstant.minus(Duration.ofHours(6)),
            lastBatchId = "previous-batch",
            exportedAt = clock.currentInstant.minus(Duration.ofHours(6)),
            totalRecordCount = 12L,
        )
        stateStore.saveCheckpoint(checkpoint)
        val error = IOException("Checkpoint unavailable")
        val failingStore = object : ExportStateStore by stateStore {
            override fun getLastCheckpoint(): ExportCheckpoint? = throw error
        }

        val result = createCoordinator(exportStateStore = failingStore).export()

        assertTrue(result is ExportCycleResult.TerminalFailure)
        val failure = result as ExportCycleResult.TerminalFailure
        assertSame(error, failure.cause)
        assertNull(failure.batch)
        assertFalse(failure.userActionRequired)
        assertTrue(failure.message.contains("Checkpoint unavailable"))
        assertNull(reader.lastQueriedWindow)
        assertTrue(destination.uploadedBatches.isEmpty())
        assertNull(stateStore.getPendingBatch())
        assertEquals(checkpoint, stateStore.getLastCheckpoint())
    }

    @Test
    fun destinationCheckExceptionIsRetryableBeforeAnyHealthReadOrStateChange() = runBlocking {
        val error = IOException("Destination unreachable")
        destination.verificationException = error

        val result = createCoordinator().export()

        assertTrue(result is ExportCycleResult.RetryableFailure)
        val failure = result as ExportCycleResult.RetryableFailure
        assertEquals("Destination check failed: Destination unreachable", failure.message)
        assertSame(error, failure.cause)
        assertNull(failure.batch)
        assertNull(reader.lastQueriedWindow)
        assertTrue(destination.uploadedBatches.isEmpty())
        assertNull(stateStore.getPendingBatch())
        assertNull(stateStore.getLastCheckpoint())
    }

    @Test
    fun readFailureWithoutMessageUsesFallbackAndLeavesExportStateUntouched() = runBlocking {
        val error = IOException()
        reader.exceptionToThrow = error

        val result = createCoordinator().export()

        assertTrue(result is ExportCycleResult.RetryableFailure)
        val failure = result as ExportCycleResult.RetryableFailure
        assertEquals("Failed reading Health Connect records: read error", failure.message)
        assertSame(error, failure.cause)
        assertNull(failure.batch)
        assertTrue(destination.uploadedBatches.isEmpty())
        assertNull(stateStore.getPendingBatch())
        assertNull(stateStore.getLastCheckpoint())
    }

    @Test
    fun permissionFailurePreservesProviderMessageAndRequiresUserAction() = runBlocking {
        val error = SecurityException("Background read permission revoked")
        reader.exceptionToThrow = error

        val result = createCoordinator().export()

        assertTrue(result is ExportCycleResult.TerminalFailure)
        val failure = result as ExportCycleResult.TerminalFailure
        assertEquals(
            "Health Connect read permission denied: Background read permission revoked",
            failure.message,
        )
        assertSame(error, failure.cause)
        assertTrue(failure.userActionRequired)
        assertNull(failure.batch)
        assertTrue(destination.uploadedBatches.isEmpty())
        assertNull(stateStore.getPendingBatch())
        assertNull(stateStore.getLastCheckpoint())
    }
}
