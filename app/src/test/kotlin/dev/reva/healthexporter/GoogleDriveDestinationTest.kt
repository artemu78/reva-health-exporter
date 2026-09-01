package dev.reva.healthexporter

import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleDriveDestinationTest {

    private val serializer = ExportBatchSerializer()

    private fun createSampleBatch(
        batchId: String = "drive-batch-001",
        installationId: String = "inst-drive-01",
        start: Instant = Instant.parse("2026-08-29T00:00:00Z"),
        end: Instant = Instant.parse("2026-08-30T00:00:00Z"),
    ): ExportBatch {
        val window = TimeWindow(startInclusive = start, endExclusive = end)
        val metadata = RecordMetadata(
            recordId = "rec-steps-1",
            origin = "com.mi.health",
            clientRecordId = "client-step-01",
        )
        val records = listOf(
            CanonicalStepsRecord(
                startTime = start.plusSeconds(3600),
                startZoneOffset = ZoneOffset.UTC,
                endTime = start.plusSeconds(5400),
                endZoneOffset = ZoneOffset.UTC,
                metadata = metadata,
                count = 2500,
            ),
            CanonicalHeartRateRecord(
                startTime = start.plusSeconds(6000),
                startZoneOffset = ZoneOffset.UTC,
                endTime = start.plusSeconds(6300),
                endZoneOffset = ZoneOffset.UTC,
                metadata = RecordMetadata(recordId = "rec-hr-1", origin = "com.mi.health"),
                samples = listOf(
                    HeartRateSample(time = start.plusSeconds(6060), beatsPerMinute = 75),
                    HeartRateSample(time = start.plusSeconds(6180), beatsPerMinute = 82),
                ),
            ),
        )
        val header = BatchHeader(
            schemaVersion = 1,
            installationId = installationId,
            batchId = batchId,
            createdAt = Instant.parse("2026-08-30T12:00:00Z"),
            timeWindow = window,
            recordCount = 2,
            recordTypes = listOf("heart_rate", "steps"),
        )
        return ExportBatch(header = header, records = records)
    }

    // 1. Fake-gateway contract tests: folder absent, folder present, duplicate folders

    @Test
    fun `ensureFolderHierarchy creates all nested folders when absent`() = runBlocking {
        val gateway = FakeGoogleDriveGateway()
        val destination = GoogleDriveDestination(driveGateway = gateway)
        val batch = createSampleBatch(start = Instant.parse("2026-08-29T00:00:00Z"))

        val result = destination.upload(batch)
        assertTrue(result is UploadResult.Success)

        // Hierarchy should have created 4 folders: Reva Health Exporter -> schema-v1 -> 2026 -> 08
        assertEquals(4, gateway.files.filter { it.mimeType == FakeGoogleDriveGateway.FOLDER_MIME_TYPE }.size)

        val rootFolder = gateway.files.find { it.name == "Reva Health Exporter" && it.parents.contains("root") }
        assertNotNull("Root folder 'Reva Health Exporter' must exist under 'root'", rootFolder)

        val schemaFolder = gateway.files.find { it.name == "schema-v1" && it.parents.contains(rootFolder!!.id) }
        assertNotNull("Schema folder 'schema-v1' must exist under root folder", schemaFolder)

        val yearFolder = gateway.files.find { it.name == "2026" && it.parents.contains(schemaFolder!!.id) }
        assertNotNull("Year folder '2026' must exist under schema folder", yearFolder)

        val monthFolder = gateway.files.find { it.name == "08" && it.parents.contains(yearFolder!!.id) }
        assertNotNull("Month folder '08' must exist under year folder", monthFolder)
    }

    @Test
    fun `ensureFolderHierarchy reuses existing folders when already present`() = runBlocking {
        val gateway = FakeGoogleDriveGateway()
        val root = gateway.addExistingFolder("f-root", "Reva Health Exporter", "root")
        val schema = gateway.addExistingFolder("f-schema", "schema-v1", root.id)
        val year = gateway.addExistingFolder("f-year", "2026", schema.id)
        val month = gateway.addExistingFolder("f-month", "08", year.id)

        val destination = GoogleDriveDestination(driveGateway = gateway)
        val batch = createSampleBatch(start = Instant.parse("2026-08-29T00:00:00Z"))

        val result = destination.upload(batch)
        assertTrue(result is UploadResult.Success)

        // No new folders should have been created
        assertEquals(0, gateway.createFolderCallCount)
        assertEquals(4, gateway.files.filter { it.mimeType == FakeGoogleDriveGateway.FOLDER_MIME_TYPE }.size)

        // Uploaded file should be placed in the existing month folder
        val uploadedFile = gateway.files.find { it.mimeType == "application/json" }
        assertNotNull(uploadedFile)
        assertTrue(uploadedFile!!.parents.contains(month.id))
    }

    @Test
    fun `ensureFolderHierarchy deterministically chooses oldest folder when duplicate folders exist`() = runBlocking {
        val gateway = FakeGoogleDriveGateway()
        // Simulate two folders with the exact same name "Reva Health Exporter" under root
        val oldestRoot = gateway.addExistingFolder(
            id = "f-root-old",
            name = "Reva Health Exporter",
            parentId = "root",
            createdTime = Instant.parse("2026-01-01T00:00:00Z"),
        )
        val newerRoot = gateway.addExistingFolder(
            id = "f-root-new",
            name = "Reva Health Exporter",
            parentId = "root",
            createdTime = Instant.parse("2026-06-01T00:00:00Z"),
        )

        val destination = GoogleDriveDestination(driveGateway = gateway)
        val batch = createSampleBatch(start = Instant.parse("2026-08-29T00:00:00Z"))

        val result = destination.upload(batch)
        assertTrue(result is UploadResult.Success)

        // The schema-v1 folder should be created under the oldest root folder
        val schemaFolder = gateway.files.find { it.name == "schema-v1" }
        assertNotNull(schemaFolder)
        assertTrue("Schema folder should be child of oldest root folder", schemaFolder!!.parents.contains(oldestRoot.id))
        assertFalse("Schema folder should not be child of newer duplicate root", schemaFolder.parents.contains(newerRoot.id))
    }

    // 2. Upload tests for success, authorization failure, forbidden, rate limit, transient server error, timeout

    @Test
    fun `upload success creates uncompressed json batch with metadata and stable appProperties`() = runBlocking {
        val gateway = FakeGoogleDriveGateway()
        val destination = GoogleDriveDestination(driveGateway = gateway)
        val batch = createSampleBatch(
            batchId = "batch-stable-xyz",
            installationId = "inst-007",
            start = Instant.parse("2026-08-29T00:00:00Z"),
            end = Instant.parse("2026-08-30T00:00:00Z"),
        )

        val result = destination.upload(batch)
        assertTrue(result is UploadResult.Success)
        val success = result as UploadResult.Success
        assertEquals("batch-stable-xyz", success.batchId)

        val uploadedFile = gateway.files.find { it.id == success.location }
        assertNotNull("Uploaded file must exist in gateway", uploadedFile)
        assertEquals("2026-08-29T000000Z--2026-08-30T000000Z--batch-stable-xyz.json", uploadedFile!!.name)
        assertEquals("application/json", uploadedFile.mimeType)
        assertEquals("batch-stable-xyz", uploadedFile.appProperties["batchId"])
        assertEquals("inst-007", uploadedFile.appProperties["installationId"])
        assertEquals("1", uploadedFile.appProperties["schemaVersion"])
    }

    @Test
    fun `downloaded json batch parses directly and passes schema validation`() = runBlocking {
        val gateway = FakeGoogleDriveGateway()
        val destination = GoogleDriveDestination(driveGateway = gateway)
        val batch = createSampleBatch()

        val result = destination.upload(batch)
        assertTrue(result is UploadResult.Success)
        val success = result as UploadResult.Success

        // Download raw bytes from Drive
        val downloadedBytes = gateway.downloadFile(success.location!!)
        assertTrue("Downloaded bytes must not be empty", downloadedBytes.isNotEmpty())

        // Parse standard JSON
        val jsonText = String(downloadedBytes, Charsets.UTF_8)
        val parsedBatch = serializer.parseJson(jsonText)
        assertEquals(batch.header.batchId, parsedBatch.header.batchId)
        assertEquals(batch.header.installationId, parsedBatch.header.installationId)
        assertEquals(batch.header.schemaVersion, parsedBatch.header.schemaVersion)
        assertEquals(batch.header.recordCount, parsedBatch.header.recordCount)
        assertEquals(batch.header.recordTypes, parsedBatch.header.recordTypes)
        assertEquals(2, parsedBatch.records.size)

        // Validate records
        val steps = parsedBatch.records.filterIsInstance<CanonicalStepsRecord>().first()
        assertEquals(2500L, steps.count)
        assertEquals("com.mi.health", steps.metadata.origin)

        val hr = parsedBatch.records.filterIsInstance<CanonicalHeartRateRecord>().first()
        assertEquals(2, hr.samples.size)
        assertEquals(75L, hr.samples[0].beatsPerMinute)
        assertEquals(82L, hr.samples[1].beatsPerMinute)
    }

    @Test
    fun `upload failure with authorization error returns non-retryable failure`() = runBlocking {
        val gateway = FakeGoogleDriveGateway()
        gateway.failOnUploadFile = GoogleDriveException.AuthorizationException("Token expired or revoked")
        val destination = GoogleDriveDestination(driveGateway = gateway)
        val batch = createSampleBatch()

        val result = destination.upload(batch)
        assertTrue(result is UploadResult.Failure)
        val failure = result as UploadResult.Failure
        assertFalse("Authorization error should not be retryable", failure.isRetryable)
        assertTrue(failure.message.contains("Token expired or revoked"))
    }

    @Test
    fun `upload failure with forbidden access returns non-retryable failure`() = runBlocking {
        val gateway = FakeGoogleDriveGateway()
        gateway.failOnUploadFile = GoogleDriveException.ForbiddenException("User does not have access to folder")
        val destination = GoogleDriveDestination(driveGateway = gateway)
        val batch = createSampleBatch()

        val result = destination.upload(batch)
        assertTrue(result is UploadResult.Failure)
        val failure = result as UploadResult.Failure
        assertFalse("Forbidden access should not be retryable", failure.isRetryable)
        assertTrue(failure.message.contains("User does not have access"))
    }

    @Test
    fun `upload failure with rate limit returns retryable failure`() = runBlocking {
        val gateway = FakeGoogleDriveGateway()
        gateway.failOnUploadFile = GoogleDriveException.RateLimitException("Drive quota/rate limit exceeded")
        val destination = GoogleDriveDestination(driveGateway = gateway)
        val batch = createSampleBatch()

        val result = destination.upload(batch)
        assertTrue(result is UploadResult.Failure)
        val failure = result as UploadResult.Failure
        assertTrue("Rate limit error should be retryable", failure.isRetryable)
        assertTrue(failure.message.contains("rate limit exceeded"))
    }

    @Test
    fun `upload failure with transient server error returns retryable failure`() = runBlocking {
        val gateway = FakeGoogleDriveGateway()
        gateway.failOnUploadFile = GoogleDriveException.TransientServerException("HTTP 503 Service Unavailable")
        val destination = GoogleDriveDestination(driveGateway = gateway)
        val batch = createSampleBatch()

        val result = destination.upload(batch)
        assertTrue(result is UploadResult.Failure)
        val failure = result as UploadResult.Failure
        assertTrue("Transient server error should be retryable", failure.isRetryable)
        assertTrue(failure.message.contains("HTTP 503"))
    }

    @Test
    fun `upload failure with timeout returns retryable failure`() = runBlocking {
        val gateway = FakeGoogleDriveGateway()
        gateway.failOnUploadFile = GoogleDriveException.TimeoutException("Socket connection timed out")
        val destination = GoogleDriveDestination(driveGateway = gateway)
        val batch = createSampleBatch()

        val result = destination.upload(batch)
        assertTrue(result is UploadResult.Failure)
        val failure = result as UploadResult.Failure
        assertTrue("Timeout error should be retryable", failure.isRetryable)
        assertTrue(failure.message.contains("timed out"))
    }

    // 3. Indeterminate success test: remote write succeeds but network response is lost

    @Test
    fun `indeterminate success recovers on retry by discovering existing batch in Drive`() = runBlocking {
        val gateway = FakeGoogleDriveGateway()
        // Simulate remote server receiving and writing the file, but connection dropping before returning HTTP response
        gateway.simulateIndeterminateUploadSuccess = true

        val destination = GoogleDriveDestination(driveGateway = gateway)
        val batch = createSampleBatch(batchId = "batch-indeterminate-001")

        // First attempt fails with timeout
        val firstResult = destination.upload(batch)
        assertTrue(firstResult is UploadResult.Failure)
        val failure = firstResult as UploadResult.Failure
        assertTrue(failure.isRetryable)

        // Verify the file was indeed written on the remote server
        assertEquals(1, gateway.files.filter { it.mimeType == "application/json" }.size)

        // Retry with the same batch: destination must detect existing file and return Success without uploading a duplicate
        val retryResult = destination.upload(batch)
        assertTrue(retryResult is UploadResult.Success)
        val success = retryResult as UploadResult.Success
        assertEquals("batch-indeterminate-001", success.batchId)

        // Invariant: still exactly 1 batch file in Drive
        val uploadedFiles = gateway.files.filter { it.mimeType == "application/json" }
        assertEquals(1, uploadedFiles.size)
        assertEquals("batch-indeterminate-001", uploadedFiles.first().appProperties["batchId"])
    }

    // 4. Idempotency test

    @Test
    fun `uploading the same batch multiple times creates no second logical file in Drive`() = runBlocking {
        val gateway = FakeGoogleDriveGateway()
        val destination = GoogleDriveDestination(driveGateway = gateway)
        val batch = createSampleBatch(batchId = "batch-idempotent-001")

        val result1 = destination.upload(batch)
        assertTrue(result1 is UploadResult.Success)

        val result2 = destination.upload(batch)
        assertTrue(result2 is UploadResult.Success)

        val result3 = destination.upload(batch)
        assertTrue(result3 is UploadResult.Success)

        assertEquals("batch-idempotent-001", (result1 as UploadResult.Success).batchId)
        assertEquals(result1.location, (result2 as UploadResult.Success).location)
        assertEquals(result1.location, (result3 as UploadResult.Success).location)

        val batchFiles = gateway.files.filter { it.mimeType == "application/json" }
        assertEquals("Exactly one file should exist in Drive for the batch", 1, batchFiles.size)
    }

    @Test
    fun `uploading an existing batch rewrites the same Drive file with current content`() = runBlocking {
        val gateway = FakeGoogleDriveGateway()
        val destination = GoogleDriveDestination(driveGateway = gateway)
        val original = createSampleBatch(batchId = "backfill-day-001")
        val replacement = original.copy(
            header = original.header.copy(
                createdAt = Instant.parse("2026-09-01T12:00:00Z"),
                recordCount = 0,
                recordTypes = emptyList(),
            ),
            records = emptyList(),
        )

        val originalResult = destination.upload(original) as UploadResult.Success
        val replacementResult = destination.upload(replacement) as UploadResult.Success

        assertEquals(originalResult.location, replacementResult.location)
        assertEquals(1, gateway.files.count { it.mimeType == "application/json" })
        assertEquals(0, serializer.parseJson(String(gateway.downloadFile(originalResult.location!!))).records.size)
    }

    // 5. Account isolation test

    @Test
    fun `account isolation ensures exports for Account A do not leak into Account B`() = runBlocking {
        val sharedBackend = FakeDriveBackend()
        val gatewayAccountA = FakeGoogleDriveGateway(accountId = "account-user-A", backend = sharedBackend)
        val gatewayAccountB = FakeGoogleDriveGateway(accountId = "account-user-B", backend = sharedBackend)

        val destA = GoogleDriveDestination(driveGateway = gatewayAccountA)
        val destB = GoogleDriveDestination(driveGateway = gatewayAccountB)

        val batchA = createSampleBatch(batchId = "batch-account-A", installationId = "inst-A")
        val batchB = createSampleBatch(batchId = "batch-account-B", installationId = "inst-B")

        val resultA = destA.upload(batchA)
        assertTrue(resultA is UploadResult.Success)

        val resultB = destB.upload(batchB)
        assertTrue(resultB is UploadResult.Success)

        // Account A's Drive has only batch A and cannot find batch B
        val filesA = gatewayAccountA.files.filter { it.mimeType == "application/json" }
        assertEquals(1, filesA.size)
        assertEquals("batch-account-A", filesA.first().appProperties["batchId"])
        val lookupBInA = gatewayAccountA.findFiles(appProperties = mapOf("batchId" to "batch-account-B"))
        assertTrue("Account A must not find Account B's batch", lookupBInA.isEmpty())

        // Account B's Drive has only batch B and cannot find batch A
        val filesB = gatewayAccountB.files.filter { it.mimeType == "application/json" }
        assertEquals(1, filesB.size)
        assertEquals("batch-account-B", filesB.first().appProperties["batchId"])
        val lookupAInB = gatewayAccountB.findFiles(appProperties = mapOf("batchId" to "batch-account-A"))
        assertTrue("Account B must not find Account A's batch", lookupAInB.isEmpty())
    }

    // 6. Destination status and verifyConfiguration tests

    @Test
    fun `verifyConfiguration returns Ready when Drive gateway is authenticated`() = runBlocking {
        val gateway = FakeGoogleDriveGateway(accountId = "connected-account")
        val destination = GoogleDriveDestination(driveGateway = gateway)

        val status = destination.verifyConfiguration()
        assertEquals(DestinationStatus.Ready, status)
    }

    @Test
    fun `verifyConfiguration returns InvalidConfiguration when authorization is missing or revoked`() = runBlocking {
        val gateway = FakeGoogleDriveGateway(accountId = null)
        val destination = GoogleDriveDestination(driveGateway = gateway)

        val status = destination.verifyConfiguration()
        assertTrue(status is DestinationStatus.InvalidConfiguration)
        val invalid = status as DestinationStatus.InvalidConfiguration
        assertTrue(invalid.message.contains("authorization required"))
    }

    @Test
    fun `verifyConfiguration returns Unavailable on transient connection failure`() = runBlocking {
        val gateway = FakeGoogleDriveGateway()
        gateway.failOnVerifyAccess = GoogleDriveException.TransientServerException("Drive service down")
        val destination = GoogleDriveDestination(driveGateway = gateway)

        val status = destination.verifyConfiguration()
        assertTrue(status is DestinationStatus.Unavailable)
    }

    // 7. Full ExportCoordinator integration with GoogleDriveDestination

    @Test
    fun `ExportCoordinator with GoogleDriveDestination successfully uploads and advances checkpoint`() = runBlocking {
        val gateway = FakeGoogleDriveGateway()
        val destination = GoogleDriveDestination(driveGateway = gateway)
        val stateStore = InMemoryExportStateStore(installationId = "inst-int-01")

        val record = CanonicalStepsRecord(
            startTime = Instant.parse("2026-08-29T10:00:00Z"),
            startZoneOffset = ZoneOffset.UTC,
            endTime = Instant.parse("2026-08-29T11:00:00Z"),
            endZoneOffset = ZoneOffset.UTC,
            metadata = RecordMetadata(recordId = "rec-int-1", origin = "com.mi.health"),
            count = 3000,
        )

        val reader = object : HealthExportRecordReader {
            override suspend fun readRecords(timeWindow: TimeWindow): List<CanonicalRecord> = listOf(record)
        }

        val testClock = object : DiagnosticClock {
            override fun now(zoneId: java.time.ZoneId) =
                Instant.parse("2026-08-30T12:00:00Z").atZone(zoneId)
        }

        val coordinator = ExportCoordinator(
            stateStore = stateStore,
            recordReader = reader,
            destination = destination,
            clock = testClock,
            idGenerator = { "batch-coord-drive-01" },
        )

        val result = coordinator.export()
        assertTrue(result is ExportCycleResult.Success)
        val success = result as ExportCycleResult.Success
        assertEquals("batch-coord-drive-01", success.batch.header.batchId)

        // Verify file in Drive
        val driveFiles = gateway.files.filter { it.mimeType == "application/json" }
        assertEquals(1, driveFiles.size)
        assertEquals("batch-coord-drive-01", driveFiles.first().appProperties["batchId"])

        // Verify checkpoint advanced
        val cp = stateStore.getLastCheckpoint()
        assertNotNull(cp)
        assertEquals("batch-coord-drive-01", cp!!.lastBatchId)
        assertEquals(1L, cp.totalRecordCount)
        assertNull(stateStore.getPendingBatch())
    }

    @Test
    fun `ExportCoordinator with GoogleDriveDestination preserves pending batch and halts checkpoint on failure`() = runBlocking {
        val gateway = FakeGoogleDriveGateway()
        gateway.failOnUploadFile = GoogleDriveException.TimeoutException("Network timeout during upload")

        val destination = GoogleDriveDestination(driveGateway = gateway)
        val stateStore = InMemoryExportStateStore(installationId = "inst-int-02")

        val record = CanonicalStepsRecord(
            startTime = Instant.parse("2026-08-29T10:00:00Z"),
            startZoneOffset = ZoneOffset.UTC,
            endTime = Instant.parse("2026-08-29T11:00:00Z"),
            endZoneOffset = ZoneOffset.UTC,
            metadata = RecordMetadata(recordId = "rec-int-2", origin = "com.mi.health"),
            count = 1500,
        )

        val reader = object : HealthExportRecordReader {
            override suspend fun readRecords(timeWindow: TimeWindow): List<CanonicalRecord> = listOf(record)
        }

        val testClock = object : DiagnosticClock {
            override fun now(zoneId: java.time.ZoneId) =
                Instant.parse("2026-08-30T12:00:00Z").atZone(zoneId)
        }

        val coordinator = ExportCoordinator(
            stateStore = stateStore,
            recordReader = reader,
            destination = destination,
            clock = testClock,
            idGenerator = { "batch-coord-fail-01" },
        )

        val result = coordinator.export()
        assertTrue(result is ExportCycleResult.RetryableFailure)

        // Invariant: checkpoint must NOT advance
        assertNull(stateStore.getLastCheckpoint())

        // Invariant: pending batch must be preserved for retry
        val pending = stateStore.getPendingBatch()
        assertNotNull(pending)
        assertEquals("batch-coord-fail-01", pending!!.header.batchId)

        // Now fix network and retry: should succeed with SAME batch ID
        gateway.failOnUploadFile = null
        val retryResult = coordinator.export()
        assertTrue(retryResult is ExportCycleResult.Success)
        val success = retryResult as ExportCycleResult.Success
        assertTrue(success.isRetry)
        assertEquals("batch-coord-fail-01", success.batch.header.batchId)

        // Checkpoint now advances
        val cp = stateStore.getLastCheckpoint()
        assertNotNull(cp)
        assertEquals("batch-coord-fail-01", cp!!.lastBatchId)
        assertNull(stateStore.getPendingBatch())
    }
}
