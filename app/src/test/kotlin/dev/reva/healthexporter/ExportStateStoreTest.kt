package dev.reva.healthexporter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class ExportStateStoreTest {

    private val serializer = ExportBatchSerializer()

    private fun createSampleBatch(batchId: String = "b-1"): ExportBatch {
        val window = TimeWindow(
            startInclusive = Instant.parse("2026-08-29T00:00:00Z"),
            endExclusive = Instant.parse("2026-08-30T00:00:00Z"),
        )
        val records = listOf(
            CanonicalStepsRecord(
                startTime = Instant.parse("2026-08-29T08:00:00Z"),
                startZoneOffset = ZoneOffset.UTC,
                endTime = Instant.parse("2026-08-29T08:15:00Z"),
                endZoneOffset = ZoneOffset.UTC,
                metadata = RecordMetadata(recordId = "r-1", origin = "com.mi.health"),
                count = 1000,
            ),
        )
        return ExportBatch(
            header = BatchHeader(
                schemaVersion = 1,
                installationId = "inst-001",
                batchId = batchId,
                createdAt = Instant.parse("2026-08-30T12:00:00Z"),
                timeWindow = window,
                recordCount = 1,
                recordTypes = listOf("steps"),
            ),
            records = records,
        )
    }

    private fun createSampleCheckpoint(batchId: String = "b-1"): ExportCheckpoint = ExportCheckpoint(
        lastWindowEnd = Instant.parse("2026-08-30T00:00:00Z"),
        lastBatchId = batchId,
        exportedAt = Instant.parse("2026-08-30T12:00:00Z"),
        totalRecordCount = 42L,
    )

    @Test
    fun inMemoryStoreManagesStateTransitionsCorrectly() {
        val store = InMemoryExportStateStore(installationId = "test-inst-01")
        assertEquals("test-inst-01", store.getInstallationId())
        assertNull(store.getLastCheckpoint())
        assertNull(store.getPendingBatch())

        // Save pending batch
        val batch = createSampleBatch("batch-42")
        store.savePendingBatch(batch)
        assertEquals(batch, store.getPendingBatch())

        // Save checkpoint
        val checkpoint = createSampleCheckpoint("batch-42")
        store.saveCheckpoint(checkpoint)
        assertEquals(checkpoint, store.getLastCheckpoint())

        // Clear pending batch
        store.clearPendingBatch()
        assertNull(store.getPendingBatch())
        assertEquals(checkpoint, store.getLastCheckpoint())

        // Clear all
        store.clear()
        assertNull(store.getLastCheckpoint())
        assertNull(store.getPendingBatch())
    }

    @Test
    fun inMemoryStoreSupportsFailureInjection() {
        val store = InMemoryExportStateStore()
        val batch = createSampleBatch()
        val checkpoint = createSampleCheckpoint()

        store.failOnSavePendingBatch = IllegalStateException("Disk write error")
        assertThrows(IllegalStateException::class.java) {
            store.savePendingBatch(batch)
        }
        assertNull(store.getPendingBatch())
        store.failOnSavePendingBatch = null

        store.failOnSaveCheckpoint = IllegalStateException("Database locked")
        assertThrows(IllegalStateException::class.java) {
            store.saveCheckpoint(checkpoint)
        }
        assertNull(store.getLastCheckpoint())
        store.failOnSaveCheckpoint = null

        store.failOnClearPendingBatch = IllegalStateException("Clear failed")
        assertThrows(IllegalStateException::class.java) {
            store.clearPendingBatch()
        }
    }

    @Test
    fun checkpointSerializationRoundTrip() {
        val checkpoint = ExportCheckpoint(
            lastWindowEnd = Instant.parse("2026-08-30T00:00:00Z"),
            lastBatchId = "batch-100",
            exportedAt = Instant.parse("2026-08-30T12:00:00Z"),
            totalRecordCount = 1500L,
        )
        val json = serializeExportCheckpoint(checkpoint)
        val deserialized = deserializeExportCheckpoint(json)
        assertEquals(checkpoint, deserialized)
    }

    @Test
    fun checkpointDeserializationHandlesCorruptJsonSafely() {
        assertNull(deserializeExportCheckpoint("{ malformed json }"))
        assertNull(deserializeExportCheckpoint(""))
        assertNull(deserializeExportCheckpoint("   "))
        assertNull(deserializeExportCheckpoint("""{"lastWindowEnd": "invalid-timestamp"}"""))
        assertNull(deserializeExportCheckpoint("""{"lastBatchId": ""}"""))
        assertNull(deserializeExportCheckpoint("""{"totalRecordCount": -5}"""))
    }

    @Test
    fun fakeSharedPreferencesStorePersistsStateAcrossInstances() {
        val sharedPrefs = FakeSharedPreferences()
        val store1 = SharedPreferencesExportStateStore(preferences = sharedPrefs)

        val instId = store1.getInstallationId()
        assertNotNull(instId)
        assertTrue(instId.isNotBlank())

        val batch = createSampleBatch("batch-persist-01")
        store1.savePendingBatch(batch)

        val checkpoint = createSampleCheckpoint("batch-persist-01")
        store1.saveCheckpoint(checkpoint)

        // Instantiate second store instance sharing the same preferences
        val store2 = SharedPreferencesExportStateStore(preferences = sharedPrefs)
        assertEquals(instId, store2.getInstallationId())
        assertEquals(checkpoint, store2.getLastCheckpoint())
        val loadedBatch = store2.getPendingBatch()
        assertNotNull(loadedBatch)
        assertEquals(batch.header.batchId, loadedBatch!!.header.batchId)
        assertEquals(batch.records.size, loadedBatch.records.size)

        // Clear pending batch in store2
        store2.clearPendingBatch()
        assertNull(store2.getPendingBatch())
        assertEquals(checkpoint, store2.getLastCheckpoint())

        // Recheck in store1
        assertNull(store1.getPendingBatch())
    }

    @Test
    fun sharedPreferencesStoreRecoversGracefullyFromCorruptData() {
        val sharedPrefs = FakeSharedPreferences()
        val store = SharedPreferencesExportStateStore(preferences = sharedPrefs)

        // Corrupt the checkpoint key directly
        sharedPrefs.edit().putString("last_export_checkpoint_json", "{ corrupted json }").apply()
        assertNull("Corrupt checkpoint should safely return null", store.getLastCheckpoint())

        // Corrupt the pending batch key directly
        sharedPrefs.edit().putString("pending_export_batch_ndjson", "invalid ndjson line 1\ninvalid line 2").apply()
        assertNull("Corrupt pending batch should safely return null", store.getPendingBatch())
    }

    @Test
    fun executionSummarySerializationRoundTrip() {
        val summary = ExportExecutionSummary(
            outcome = ExportOutcome.SUCCESS,
            batchId = "batch-100",
            recordCount = 42,
            executionTimestamp = Instant.parse("2026-08-30T12:00:00Z"),
            message = "Successfully exported batch batch-100 (42 records)",
            destinationLocation = "https://drive.google.com/file/123",
        )
        val json = serializeExportExecutionSummary(summary)
        val deserialized = deserializeExportExecutionSummary(json)
        assertEquals(summary, deserialized)
    }

    @Test
    fun executionSummaryDeserializationHandlesCorruptJsonSafely() {
        assertNull(deserializeExportExecutionSummary("{ malformed json }"))
        assertNull(deserializeExportExecutionSummary(""))
        assertNull(deserializeExportExecutionSummary("   "))
        assertNull(deserializeExportExecutionSummary("""{"outcome": "UNKNOWN_OUTCOME"}"""))
        assertNull(deserializeExportExecutionSummary("""{"outcome": "SUCCESS", "executionTimestamp": "not-a-date"}"""))
    }

    @Test
    fun inMemoryStoreManagesExecutionSummary() {
        val store = InMemoryExportStateStore()
        assertNull(store.getLastExecutionSummary())

        val summary = ExportExecutionSummary(
            outcome = ExportOutcome.SUCCESS,
            batchId = "batch-mem-01",
            recordCount = 5,
            executionTimestamp = Instant.parse("2026-08-30T12:00:00Z"),
            message = "Success",
        )
        store.saveExecutionSummary(summary)
        assertEquals(summary, store.getLastExecutionSummary())

        store.clear()
        assertNull(store.getLastExecutionSummary())
    }

    @Test
    fun sharedPreferencesStorePersistsExecutionSummaryAcrossInstances() {
        val sharedPrefs = FakeSharedPreferences()
        val store1 = SharedPreferencesExportStateStore(preferences = sharedPrefs)

        val summary = ExportExecutionSummary(
            outcome = ExportOutcome.RETRYABLE_FAILURE,
            batchId = "batch-retry-01",
            recordCount = 10,
            executionTimestamp = Instant.parse("2026-08-30T12:00:00Z"),
            message = "Transient server error 503",
        )
        store1.saveExecutionSummary(summary)

        val store2 = SharedPreferencesExportStateStore(preferences = sharedPrefs)
        assertEquals(summary, store2.getLastExecutionSummary())

        // Corrupt summary in prefs
        sharedPrefs.edit().putString("last_export_execution_summary_json", "broken json").apply()
        assertNull(store2.getLastExecutionSummary())
    }
}

