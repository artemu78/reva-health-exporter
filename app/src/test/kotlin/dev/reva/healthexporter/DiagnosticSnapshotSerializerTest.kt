package dev.reva.healthexporter

import java.time.Instant
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticSnapshotSerializerTest {
    private val serializer = DiagnosticSnapshotSerializer()

    @Test
    fun `serialization is deterministic and matches the synthetic golden fixture`() {
        val snapshot = syntheticSnapshot()

        val first = serializer.serialize(snapshot)
        val second = serializer.serialize(snapshot.copy(types = snapshot.types.reversed()))
        val golden = Path.of(System.getProperty("user.dir"))
            .resolve("src/test/resources/diagnostic-snapshot-v1.json")
            .readText()

        assertEquals(first, second)
        assertEquals(golden.trimEnd(), first)
    }

    @Test
    fun `produced JSON parses outside the app and preserves required metadata`() {
        val parsed = serializer.parse(serializer.serialize(syntheticSnapshot()))

        assertEquals(1, parsed.schemaVersion)
        assertEquals("1.0.0", parsed.appVersion)
        assertEquals("Android 11 (API 30)", parsed.androidVersion)
        assertEquals(setOf("Steps"), parsed.permissions.granted.toSet())
        assertEquals(2, parsed.types.single { it.type == "Steps" }.count)
    }

    @Test
    fun `malformed and structurally invalid input is rejected`() {
        assertThrows(InvalidDiagnosticSnapshotException::class.java) {
            serializer.parse("{\"schemaVersion\":1,")
        }
        assertThrows(InvalidDiagnosticSnapshotException::class.java) {
            serializer.parse("{\"schemaVersion\":2,\"types\":[]}")
        }
    }

    @Test
    fun `default snapshot contains no raw values tokens credentials or account identifiers`() {
        val json = serializer.serialize(syntheticSnapshot()).lowercase()

        listOf("rawvalue", "raw_value", "token", "credential", "account", "email", "samples")
            .forEach { forbidden -> assertFalse("forbidden field $forbidden", json.contains(forbidden)) }
        assertFalse(json.contains("72"))
        assertTrue(json.contains("count"))
    }

    @Test
    fun `snapshot built from probe result excludes record previews`() {
        val result = DiagnosticProbeResult(
            window = ProbeTimeWindow(
                Instant.parse("2026-08-29T09:00:00Z"),
                Instant.parse("2026-08-30T09:00:00Z"),
            ),
            summaries = mapOf(
                HealthMetric.STEPS to MetricProbeSummary(
                    metric = HealthMetric.STEPS,
                    status = MetricProbeStatus.POPULATED,
                    count = 1,
                    oldestTimestamp = Instant.parse("2026-08-30T07:00:00Z"),
                    newestTimestamp = Instant.parse("2026-08-30T08:00:00Z"),
                    dataOrigins = setOf("com.example.synthetic"),
                    previews = listOf(
                        MetricRecordPreview(
                            Instant.parse("2026-08-30T07:00:00Z"),
                            Instant.parse("2026-08-30T08:00:00Z"),
                            "private.preview.origin",
                        ),
                    ),
                ),
            ),
        )

        val json = serializer.serialize(
            diagnosticSnapshot(result, "1.0.0", "Android 11 (API 30)", setOf(HealthMetric.STEPS)),
        )

        assertFalse(json.contains("previews"))
        assertFalse(json.contains("private.preview.origin"))
    }

    private fun syntheticSnapshot() = DiagnosticSnapshot(
        schemaVersion = 1,
        appVersion = "1.0.0",
        androidVersion = "Android 11 (API 30)",
        permissions = SnapshotPermissions(
            granted = listOf("Steps"),
            missing = listOf("Heart rate"),
        ),
        window = SnapshotTimeCoverage(
            oldest = Instant.parse("2026-08-29T09:00:00Z"),
            newest = Instant.parse("2026-08-30T09:00:00Z"),
        ),
        types = listOf(
            SnapshotTypeSummary(
                type = "Steps",
                status = "populated",
                count = 2,
                origins = listOf("com.example.synthetic"),
                timeCoverage = SnapshotTimeCoverage(
                    oldest = Instant.parse("2026-08-30T07:00:00Z"),
                    newest = Instant.parse("2026-08-30T08:00:00Z"),
                ),
            ),
            SnapshotTypeSummary(
                type = "Heart rate",
                status = "permission_missing",
                count = 0,
                origins = emptyList(),
                timeCoverage = null,
            ),
        ),
    )
}
