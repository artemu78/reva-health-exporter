package dev.reva.healthexporter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking

class LocalFileDestinationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val serializer = ExportBatchSerializer()

    private fun createSampleBatch(
        batchId: String = "test-batch-001",
        start: Instant = Instant.parse("2026-08-29T00:00:00Z"),
        end: Instant = Instant.parse("2026-08-30T00:00:00Z"),
    ): ExportBatch {
        val window = TimeWindow(startInclusive = start, endExclusive = end)
        val metadata = RecordMetadata(
            recordId = "rec-steps-1",
            origin = "com.mi.health",
        )
        val records = listOf(
            CanonicalStepsRecord(
                startTime = start.plusSeconds(3600),
                startZoneOffset = ZoneOffset.UTC,
                endTime = start.plusSeconds(5400),
                endZoneOffset = ZoneOffset.UTC,
                metadata = metadata,
                count = 1200,
            ),
        )
        val header = BatchHeader(
            schemaVersion = 1,
            installationId = "inst-001",
            batchId = batchId,
            createdAt = Instant.parse("2026-08-30T12:00:00Z"),
            timeWindow = window,
            recordCount = 1,
            recordTypes = listOf("steps"),
        )
        return ExportBatch(header = header, records = records)
    }

    @Test
    fun verifyConfigurationReturnsReadyForValidDirectory() = runBlocking {
        val dir = tempFolder.newFolder("exports")
        val destination = LocalFileDestination(baseDirectory = dir)

        val status = destination.verifyConfiguration()
        assertEquals(DestinationStatus.Ready, status)
    }

    @Test
    fun verifyConfigurationReturnsReadyWhenDirectoryNeedsCreation() = runBlocking {
        val parent = tempFolder.newFolder("parent")
        val dir = File(parent, "nested/exports")
        val destination = LocalFileDestination(baseDirectory = dir)

        val status = destination.verifyConfiguration()
        assertEquals(DestinationStatus.Ready, status)
    }

    @Test
    fun uploadWritesValidJsonBatch() = runBlocking {
        val dir = tempFolder.newFolder("exports")
        val destination = LocalFileDestination(baseDirectory = dir, useHierarchy = true)
        val batch = createSampleBatch()

        val result = destination.upload(batch)
        assertTrue(result is UploadResult.Success)
        val success = result as UploadResult.Success
        assertEquals("test-batch-001", success.batchId)
        assertNotNull(success.location)

        val writtenFile = File(success.location!!)
        assertTrue("Written file should exist", writtenFile.exists())
        assertTrue("Filename should contain batchId", writtenFile.name.contains("test-batch-001"))
        assertTrue("Filename should end with .json", writtenFile.name.endsWith(".json"))

        // Check directory hierarchy 2026/08
        val parentDir = writtenFile.parentFile
        assertEquals("08", parentDir?.name)
        assertEquals("2026", parentDir?.parentFile?.name)

        // Parse the written JSON file directly to ensure schema validity
        val parsedBatch = serializer.parseJson(writtenFile.readText(Charsets.UTF_8))
        assertEquals(batch.header.batchId, parsedBatch.header.batchId)
        assertEquals(batch.header.recordCount, parsedBatch.header.recordCount)
        assertEquals(1, parsedBatch.records.size)
        assertEquals("steps", parsedBatch.records.first().recordType)
    }

    @Test
    fun uploadWithoutHierarchyWritesDirectlyToBaseDir() = runBlocking {
        val dir = tempFolder.newFolder("flat_exports")
        val destination = LocalFileDestination(baseDirectory = dir, useHierarchy = false)
        val batch = createSampleBatch()

        val result = destination.upload(batch)
        assertTrue(result is UploadResult.Success)
        val success = result as UploadResult.Success

        val writtenFile = File(success.location!!)
        assertEquals(dir.canonicalPath, writtenFile.parentFile?.canonicalPath)
    }

    @Test
    fun uploadIsIdempotentWhenOverwritingSameBatch() = runBlocking {
        val dir = tempFolder.newFolder("exports")
        val destination = LocalFileDestination(baseDirectory = dir)
        val batch = createSampleBatch()

        val result1 = destination.upload(batch)
        assertTrue(result1 is UploadResult.Success)

        val result2 = destination.upload(batch)
        assertTrue(result2 is UploadResult.Success)

        val file1 = File((result1 as UploadResult.Success).location!!)
        val file2 = File((result2 as UploadResult.Success).location!!)
        assertEquals(file1.canonicalPath, file2.canonicalPath)
        assertTrue(file1.exists())
    }

    @Test
    fun atomicWriteCleansUpTemporaryFileOnFailure() = runBlocking {
        val dir = tempFolder.newFolder("exports")
        val failingSerializer = object : ExportBatchSerializer() {
            override fun serializeToJson(batch: ExportBatch): String {
                throw IOException("Simulated disk error during serialization")
            }
        }
        val destination = LocalFileDestination(baseDirectory = dir, serializer = failingSerializer)
        val batch = createSampleBatch()

        val result = destination.upload(batch)
        assertTrue(result is UploadResult.Failure)
        val failure = result as UploadResult.Failure
        assertTrue(failure.isRetryable)
        assertTrue(failure.message.contains("Simulated disk error"))

        // Verify no leftover .tmp files or destination file
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        assertTrue("No partial files should remain in destination dir", files.isEmpty())
    }

    @Test
    fun verifyConfigurationReturnsInvalidConfigurationWhenBasePathIsAFile() = runBlocking {
        val file = tempFolder.newFile("not_a_dir.txt")
        val destination = LocalFileDestination(baseDirectory = file)

        val status = destination.verifyConfiguration()
        assertTrue(status is DestinationStatus.InvalidConfiguration)
        val invalid = status as DestinationStatus.InvalidConfiguration
        assertTrue(invalid.message.contains("not a directory"))
    }

    @Test
    fun formatBatchFilenameProducesDeterministicStandardName() {
        val window = TimeWindow(
            startInclusive = Instant.parse("2026-08-29T00:00:00Z"),
            endExclusive = Instant.parse("2026-08-30T00:00:00Z"),
        )
        val header = BatchHeader(
            schemaVersion = 1,
            installationId = "inst-01",
            batchId = "batch-123",
            createdAt = Instant.parse("2026-08-30T12:00:00Z"),
            timeWindow = window,
            recordCount = 0,
            recordTypes = emptyList(),
        )

        val filename = LocalFileDestination.formatBatchFilename(header)
        assertEquals("2026-08-29T000000Z--2026-08-30T000000Z--batch-123.json", filename)
    }
}
