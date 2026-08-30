package dev.reva.healthexporter

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackgroundProbeStoreTest {
    @Test
    fun inMemoryStoreSavesAndLoadsSummary() {
        val store = InMemoryBackgroundProbeStore()
        assertNull(store.loadSummary())

        val summary = BackgroundReadExecutionSummary(
            outcome = BackgroundReadOutcome.SUCCESS,
            message = "Read 12 records across 5 types",
            totalRecords = 12,
            readTypesCount = 5,
            executionTimestamp = Instant.parse("2026-08-30T12:00:00Z"),
            dataOrigins = setOf("com.mi.health"),
        )

        store.saveSummary(summary)
        assertEquals(summary, store.loadSummary())

        store.clear()
        assertNull(store.loadSummary())
    }

    @Test
    fun serializationPreservesAllSummaryFieldsWithoutHealthValues() {
        val original = BackgroundReadExecutionSummary(
            outcome = BackgroundReadOutcome.USER_ACTION_REQUIRED,
            message = "Permission revoked",
            totalRecords = 0,
            readTypesCount = 0,
            executionTimestamp = Instant.parse("2026-08-30T14:30:00Z"),
            dataOrigins = emptySet(),
        )

        val serialized = serializeBackgroundProbeSummary(original)
        val deserialized = deserializeBackgroundProbeSummary(serialized)

        assertEquals(original, deserialized)
    }

    @Test
    fun corruptSerializedDataReturnsNull() {
        assertNull(deserializeBackgroundProbeSummary("{ invalid json }"))
        assertNull(deserializeBackgroundProbeSummary(""))
        assertNull(deserializeBackgroundProbeSummary("""{"unknown_field": 123}"""))
    }
}
