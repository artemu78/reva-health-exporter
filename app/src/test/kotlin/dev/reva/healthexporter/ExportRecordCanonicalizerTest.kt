package dev.reva.healthexporter

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportRecordCanonicalizerTest {
    private val start = Instant.parse("2026-09-03T23:00:00Z")

    @Test
    fun expandingSnapshotsAndExactDuplicatesCollapseToLatestSession() {
        val snapshots = listOf(
            sleep("revision-1", start, "2026-09-04T01:38:00Z"),
            sleep("revision-2", start, "2026-09-04T02:22:00Z"),
            sleep("revision-3", start, "2026-09-04T05:06:00Z"),
            sleep("revision-4", start, "2026-09-04T06:54:00Z"),
        )

        val canonical = ExportRecordCanonicalizer.canonicalize(snapshots + snapshots[3])

        assertEquals(1, canonical.size)
        assertEquals(Instant.parse("2026-09-04T06:54:00Z"), canonical.single().endTime)
    }

    @Test
    fun separateSessionsOnSameDayRemainSeparate() {
        val night = sleep("night", start, "2026-09-04T06:54:00Z")
        val napStart = Instant.parse("2026-09-04T12:00:00Z")
        val nap = sleep("nap", napStart, "2026-09-04T12:45:00Z")

        val canonical = ExportRecordCanonicalizer.canonicalize(listOf(night, nap))

        assertEquals(setOf(start, napStart), canonical.map { it.startTime }.toSet())
    }

    @Test
    fun equalStartTimesFromDifferentOriginsRemainSeparate() {
        val xiaomi = sleep("xiaomi", start, "2026-09-04T06:54:00Z")
        val other = sleep(
            id = "other",
            startTime = start,
            endTime = "2026-09-04T06:54:00Z",
            origin = "example.other.origin",
        )

        val canonical = ExportRecordCanonicalizer.canonicalize(listOf(xiaomi, other))

        assertEquals(2, canonical.size)
        assertEquals(setOf("com.xiaomi.wearable", "example.other.origin"), canonical.map { it.metadata.origin }.toSet())
    }

    @Test
    fun equalEndTimePrefersGreaterStageCoverageThenStageCount() {
        val end = Instant.parse("2026-09-04T06:54:00Z")
        val sparse = sleep("sparse", start, end.toString(), stages = emptyList())
        val complete = sleep(
            "complete",
            start,
            end.toString(),
            stages = listOf(
                SleepStage(start, Instant.parse("2026-09-04T03:00:00Z"), 4),
                SleepStage(Instant.parse("2026-09-04T03:00:00Z"), end, 5),
            ),
        )

        val canonical = ExportRecordCanonicalizer.canonicalize(listOf(sparse, complete))

        assertEquals("complete", canonical.single().metadata.recordId)
        assertEquals(2, (canonical.single() as CanonicalSleepSessionRecord).stages.size)
    }

    @Test
    fun emptyStagesAreAcceptedAndLatestEndStillWins() {
        val shorterWithStages = sleep(
            "shorter",
            start,
            "2026-09-04T05:06:00Z",
            stages = listOf(SleepStage(start, Instant.parse("2026-09-04T05:06:00Z"), 4)),
        )
        val latestWithoutStages = sleep("latest", start, "2026-09-04T06:54:00Z", stages = emptyList())

        val canonical = ExportRecordCanonicalizer.canonicalize(listOf(shorterWithStages, latestWithoutStages))

        assertEquals("latest", canonical.single().metadata.recordId)
        assertTrue((canonical.single() as CanonicalSleepSessionRecord).stages.isEmpty())
    }

    @Test
    fun stableSourceIdLinksRevisionsEvenIfTheirStartTimesDiffer() {
        val first = sleep("stable-id", start, "2026-09-04T05:06:00Z")
        val correctedStart = start.plusSeconds(60)
        val latest = sleep("stable-id", correctedStart, "2026-09-04T06:54:00Z")

        val canonical = ExportRecordCanonicalizer.canonicalize(listOf(first, latest))

        assertEquals(1, canonical.size)
        assertEquals(correctedStart, canonical.single().startTime)
    }

    @Test
    fun exportedJsonIsDeterministicRegardlessOfInputOrder() {
        val records = listOf(
            sleep("revision-1", start, "2026-09-04T01:38:00Z"),
            sleep("revision-2", start, "2026-09-04T06:54:00Z"),
            sleep("nap", Instant.parse("2026-09-04T12:00:00Z"), "2026-09-04T12:45:00Z"),
        )

        val forward = batch(ExportRecordCanonicalizer.canonicalize(records))
        val reversed = batch(ExportRecordCanonicalizer.canonicalize(records.reversed()))

        assertEquals(
            ExportBatchSerializer().serializeToJson(forward),
            ExportBatchSerializer().serializeToJson(reversed),
        )
    }

    private fun sleep(
        id: String,
        startTime: Instant,
        endTime: String,
        origin: String = "com.xiaomi.wearable",
        stages: List<SleepStage>? = null,
    ): CanonicalSleepSessionRecord {
        val end = Instant.parse(endTime)
        return CanonicalSleepSessionRecord(
            startTime = startTime,
            startZoneOffset = ZoneOffset.UTC,
            endTime = end,
            endZoneOffset = ZoneOffset.UTC,
            metadata = RecordMetadata(recordId = id, origin = origin),
            stages = stages ?: listOf(SleepStage(startTime, end, 4)),
        )
    }

    private fun batch(records: List<CanonicalRecord>): ExportBatch = ExportBatch(
        header = BatchHeader(
            installationId = "installation",
            batchId = "batch",
            createdAt = Instant.parse("2026-09-04T13:00:00Z"),
            timeWindow = TimeWindow(
                Instant.parse("2026-09-03T00:00:00Z"),
                Instant.parse("2026-09-05T00:00:00Z"),
            ),
            recordCount = records.size,
            recordTypes = records.map { it.recordType }.distinct().sorted(),
        ),
        records = records,
    )
}
