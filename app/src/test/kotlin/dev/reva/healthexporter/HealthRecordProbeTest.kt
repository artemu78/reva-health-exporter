package dev.reva.healthexporter

import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.testing.FakeHealthConnectClient
import androidx.health.connect.client.testing.stubs.Stub
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Percentage
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HealthRecordProbeTest {
    @Test
    fun emptyDataIsDistinctForEveryCandidate() = runBlocking {
        val result = HealthRecordProbe(FakeHealthConnectClient()).probe(window, allPermissions)

        HealthMetric.entries.forEach { metric ->
            assertEquals(
                MetricProbeSummary(metric = metric, status = MetricProbeStatus.EMPTY),
                result.summaries.getValue(metric),
            )
        }
    }

    @Test
    fun populatedRecordsCollectMultipleOrigins() = runBlocking {
        val client = FakeHealthConnectClient()
        client.setPackageName("com.example.mi.fitness")
        client.insertRecords(listOf(steps("2026-08-29T08:00:00Z", "2026-08-29T08:15:00Z")))
        client.setPackageName("com.example.phone")
        client.insertRecords(listOf(steps("2026-08-29T09:00:00Z", "2026-08-29T09:15:00Z")))

        val summary = HealthRecordProbe(client)
            .probe(window, allPermissions)
            .summaries
            .getValue(HealthMetric.STEPS)

        assertEquals(setOf("com.example.mi.fitness", "com.example.phone"), summary.dataOrigins)
    }

    @Test
    fun allPagesContributeToTheSummary() = runBlocking {
        val client = FakeHealthConnectClient()
        client.insertRecords(
            listOf(
                steps("2026-08-29T08:00:00Z", "2026-08-29T08:15:00Z"),
                steps("2026-08-29T09:00:00Z", "2026-08-29T09:15:00Z"),
                steps("2026-08-29T10:00:00Z", "2026-08-29T10:15:00Z"),
            ),
        )

        val summary = HealthRecordProbe(client, pageSize = 1)
            .probe(window, allPermissions)
            .summaries
            .getValue(HealthMetric.STEPS)

        assertEquals(3, summary.count)
        assertEquals(Instant.parse("2026-08-29T08:00:00Z"), summary.oldestTimestamp)
        assertEquals(Instant.parse("2026-08-29T10:15:00Z"), summary.newestTimestamp)
    }

    @Test
    fun missingPermissionIsDistinctFromEmptyData() = runBlocking {
        val stepsPermission = HealthConnectConfiguration.permissionByMetric.getValue(HealthMetric.STEPS)

        val result = HealthRecordProbe(FakeHealthConnectClient()).probe(
            window,
            allPermissions - stepsPermission,
        )

        assertEquals(
            MetricProbeSummary(
                metric = HealthMetric.STEPS,
                status = MetricProbeStatus.PERMISSION_MISSING,
            ),
            result.summaries.getValue(HealthMetric.STEPS),
        )
        assertEquals(
            MetricProbeStatus.EMPTY,
            result.summaries.getValue(HealthMetric.HEART_RATE).status,
        )
    }

    @Test
    fun oneTypeFailureDoesNotAbortTheDiagnosticRun() = runBlocking {
        val client = FakeHealthConnectClient()
        client.overrides.readRecords = Stub { request ->
            if (request.recordType == StepsRecord::class) throw IOException("provider read failed")
            null
        }

        val result = HealthRecordProbe(client).probe(window, allPermissions)

        assertEquals(MetricProbeStatus.FAILED, result.summaries.getValue(HealthMetric.STEPS).status)
        assertEquals(
            MetricProbeStatus.EMPTY,
            result.summaries.getValue(HealthMetric.HEART_RATE).status,
        )
        assertEquals(HealthMetric.entries.toSet(), result.summaries.keys)
    }

    @Test
    fun unsupportedTypeIsDistinctFromProviderFailure() = runBlocking {
        val client = FakeHealthConnectClient()
        client.overrides.readRecords = Stub { request ->
            if (request.recordType == OxygenSaturationRecord::class) {
                throw UnsupportedOperationException("record type unavailable")
            }
            null
        }

        val result = HealthRecordProbe(client).probe(window, allPermissions)

        assertEquals(
            MetricProbeStatus.UNSUPPORTED,
            result.summaries.getValue(HealthMetric.OXYGEN_SATURATION).status,
        )
    }

    @Test
    fun blankTerminalPageTokenKeepsSuccessfulPageAvailable() = runBlocking {
        val client = FakeHealthConnectClient()
        var stepsCalls = 0
        client.overrides.readRecords = Stub { request ->
            if (request.recordType != StepsRecord::class) return@Stub null
            stepsCalls += 1
            if (stepsCalls > 1) throw IOException("blank token is not a next page")
            ReadRecordsResponse(
                records = listOf(steps("2026-08-29T08:00:00Z", "2026-08-29T08:15:00Z")),
                pageToken = "",
            )
        }

        val summary = HealthRecordProbe(client)
            .probe(window, allPermissions)
            .summaries
            .getValue(HealthMetric.STEPS)

        assertEquals(MetricProbeStatus.POPULATED, summary.status)
        assertEquals(1, summary.count)
        assertEquals(1, stepsCalls)
    }

    @Test
    fun failedPageKeepsEarlierPageMetadata() = runBlocking {
        val client = FakeHealthConnectClient()
        client.overrides.readRecords = Stub { request ->
            when {
                request.recordType != StepsRecord::class -> null
                request.pageToken == null -> ReadRecordsResponse(
                    records = listOf(steps("2026-08-29T08:00:00Z", "2026-08-29T08:15:00Z")),
                    pageToken = "expired-token",
                )
                else -> throw IOException("page token expired")
            }
        }

        val summary = HealthRecordProbe(client)
            .probe(window, allPermissions)
            .summaries
            .getValue(HealthMetric.STEPS)

        assertEquals(MetricProbeStatus.FAILED, summary.status)
        assertEquals(1, summary.count)
        assertEquals(Instant.parse("2026-08-29T08:00:00Z"), summary.oldestTimestamp)
        assertEquals(Instant.parse("2026-08-29T08:15:00Z"), summary.newestTimestamp)
    }

    @Test
    fun permissionRevokedAfterFirstPageKeepsPartialMetadata() = runBlocking {
        val client = FakeHealthConnectClient()
        client.overrides.readRecords = Stub { request ->
            when {
                request.recordType != StepsRecord::class -> null
                request.pageToken == null -> ReadRecordsResponse(
                    records = listOf(steps("2026-08-29T08:00:00Z", "2026-08-29T08:15:00Z")),
                    pageToken = "next-page",
                )
                else -> throw SecurityException("permission revoked")
            }
        }

        val summary = HealthRecordProbe(client)
            .probe(window, allPermissions)
            .summaries
            .getValue(HealthMetric.STEPS)

        assertEquals(MetricProbeStatus.PERMISSION_MISSING, summary.status)
        assertEquals(1, summary.count)
    }

    @Test
    fun repeatedPageTokenFailsWithoutLoopingForever() = runBlocking {
        val client = FakeHealthConnectClient()
        var stepsCalls = 0
        client.overrides.readRecords = Stub { request ->
            if (request.recordType != StepsRecord::class) return@Stub null
            stepsCalls += 1
            if (stepsCalls > 2) throw AssertionError("Repeated page token was requested again")
            ReadRecordsResponse(
                records = listOf(steps("2026-08-29T08:00:00Z", "2026-08-29T08:15:00Z")),
                pageToken = "same-token",
            )
        }

        val summary = HealthRecordProbe(client)
            .probe(window, allPermissions)
            .summaries
            .getValue(HealthMetric.STEPS)

        assertEquals(MetricProbeStatus.FAILED, summary.status)
        assertEquals(2, summary.count)
        assertEquals(2, stepsCalls)
    }

    @Test
    fun cancellationIsPropagated() {
        val client = FakeHealthConnectClient()
        client.overrides.readRecords = Stub { throw CancellationException("cancelled") }

        assertThrows(CancellationException::class.java) {
            runBlocking { HealthRecordProbe(client).probe(window, allPermissions) }
        }
    }

    @Test
    fun timeWindowIncludesStartAndExcludesEnd() = runBlocking {
        val client = FakeHealthConnectClient()
        client.insertRecords(
            listOf(
                restingHeartRate(window.startInclusive),
                restingHeartRate(window.endExclusive.minusMillis(1)),
                restingHeartRate(window.endExclusive),
            ),
        )

        val summary = HealthRecordProbe(client)
            .probe(window, allPermissions)
            .summaries
            .getValue(HealthMetric.RESTING_HEART_RATE)

        assertEquals(2, summary.count)
        assertEquals(window.startInclusive, summary.oldestTimestamp)
        assertEquals(window.endExclusive.minusMillis(1), summary.newestTimestamp)
    }

    @Test
    fun localDayWindowKeepsMidnightAcrossSpringDstTransition() {
        val zone = ZoneId.of("Europe/Berlin")
        val endAtMidnight = ZonedDateTime.parse("2026-03-30T00:00:00+02:00[Europe/Berlin]")

        val result = ProbeTimeWindow.previousLocalDays(endAtMidnight, days = 1)

        assertEquals(Instant.parse("2026-03-28T23:00:00Z"), result.startInclusive)
        assertEquals(Instant.parse("2026-03-29T22:00:00Z"), result.endExclusive)
        assertEquals(Duration.ofHours(23), Duration.between(result.startInclusive, result.endExclusive))
        assertEquals(zone, endAtMidnight.zone)
    }

    @Test
    fun localDayWindowKeepsMidnightAcrossAutumnDstTransition() {
        val endAtMidnight = ZonedDateTime.parse("2026-10-26T00:00:00+01:00[Europe/Berlin]")

        val result = ProbeTimeWindow.previousLocalDays(endAtMidnight, days = 1)

        assertEquals(Instant.parse("2026-10-24T22:00:00Z"), result.startInclusive)
        assertEquals(Instant.parse("2026-10-25T23:00:00Z"), result.endExclusive)
        assertEquals(Duration.ofHours(25), Duration.between(result.startInclusive, result.endExclusive))
    }

    @Test
    fun everyCandidateTypeIsProbed() = runBlocking {
        val client = FakeHealthConnectClient()
        client.setPackageName("com.example.mi.fitness")
        client.insertRecords(candidateRecords())

        val result = HealthRecordProbe(client).probe(window, allPermissions)

        assertEquals(HealthMetric.entries.toSet(), result.summaries.keys)
        HealthMetric.entries.forEach { metric ->
            val summary = result.summaries.getValue(metric)
            assertEquals("$metric status", MetricProbeStatus.POPULATED, summary.status)
            assertEquals("$metric count", 1, summary.count)
            assertEquals("$metric oldest", START, summary.oldestTimestamp)
            assertEquals("$metric newest", expectedNewest(metric), summary.newestTimestamp)
            assertEquals(setOf("com.example.mi.fitness"), summary.dataOrigins)
        }
    }

    @Test
    fun populatedStepsReportCountCoverageAndOrigin() = runBlocking {
        val client = FakeHealthConnectClient()
        client.setPackageName("com.example.mi.fitness")
        client.insertRecords(
            listOf(
                steps("2026-08-29T08:00:00Z", "2026-08-29T08:15:00Z"),
                steps("2026-08-29T09:00:00Z", "2026-08-29T09:20:00Z"),
            ),
        )

        val result = HealthRecordProbe(client).probe(window, allPermissions)

        assertEquals(
            MetricProbeSummary(
                metric = HealthMetric.STEPS,
                status = MetricProbeStatus.POPULATED,
                count = 2,
                oldestTimestamp = Instant.parse("2026-08-29T08:00:00Z"),
                newestTimestamp = Instant.parse("2026-08-29T09:20:00Z"),
                dataOrigins = setOf("com.example.mi.fitness"),
                previews = listOf(
                    MetricRecordPreview(
                        startTimestamp = Instant.parse("2026-08-29T09:00:00Z"),
                        endTimestamp = Instant.parse("2026-08-29T09:20:00Z"),
                        dataOrigin = "com.example.mi.fitness",
                    ),
                    MetricRecordPreview(
                        startTimestamp = Instant.parse("2026-08-29T08:00:00Z"),
                        endTimestamp = Instant.parse("2026-08-29T08:15:00Z"),
                        dataOrigin = "com.example.mi.fitness",
                    ),
                ),
            ),
            result.summaries.getValue(HealthMetric.STEPS),
        )
    }

    @Test
    fun recordPreviewContainsOnlyThreeMostRecentMetadataEntries() = runBlocking {
        val client = FakeHealthConnectClient()
        client.setPackageName("com.example.mi.fitness")
        client.insertRecords(
            (1..5).map { hour ->
                steps("2026-08-29T0${hour}:00:00Z", "2026-08-29T0${hour}:15:00Z")
            },
        )

        val preview = HealthRecordProbe(client)
            .probe(window, allPermissions)
            .summaries
            .getValue(HealthMetric.STEPS)
            .previews

        assertEquals(3, preview.size)
        assertEquals(
            listOf(
                Instant.parse("2026-08-29T05:15:00Z"),
                Instant.parse("2026-08-29T04:15:00Z"),
                Instant.parse("2026-08-29T03:15:00Z"),
            ),
            preview.map(MetricRecordPreview::endTimestamp),
        )
        assertEquals(setOf("com.example.mi.fitness"), preview.mapNotNull { it.dataOrigin }.toSet())
    }

    private fun steps(start: String, end: String) = StepsRecord(
        startTime = Instant.parse(start),
        startZoneOffset = null,
        endTime = Instant.parse(end),
        endZoneOffset = null,
        count = 100,
        metadata = Metadata.manualEntry(),
    )

    private fun restingHeartRate(time: Instant) = RestingHeartRateRecord(
        time = time,
        zoneOffset = null,
        beatsPerMinute = 60,
        metadata = Metadata.manualEntry(),
    )

    private fun candidateRecords(): List<Record> = listOf(
        steps(START.toString(), END.toString()),
        HeartRateRecord(
            startTime = START,
            startZoneOffset = null,
            endTime = END,
            endZoneOffset = null,
            samples = listOf(HeartRateRecord.Sample(START.plusSeconds(30), 80)),
            metadata = Metadata.manualEntry(),
        ),
        RestingHeartRateRecord(START, null, 60, Metadata.manualEntry()),
        SleepSessionRecord(START, null, END, null, Metadata.manualEntry()),
        DistanceRecord(START, null, END, null, Length.meters(250.0), Metadata.manualEntry()),
        TotalCaloriesBurnedRecord(
            START,
            null,
            END,
            null,
            Energy.kilocalories(25.0),
            Metadata.manualEntry(),
        ),
        ExerciseSessionRecord(
            startTime = START,
            startZoneOffset = null,
            endTime = END,
            endZoneOffset = null,
            metadata = Metadata.manualEntry(),
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
        ),
        OxygenSaturationRecord(START, null, Percentage(97.0), Metadata.manualEntry()),
    )

    private fun expectedNewest(metric: HealthMetric): Instant = when (metric) {
        HealthMetric.RESTING_HEART_RATE,
        HealthMetric.OXYGEN_SATURATION,
        -> START
        else -> END
    }

    private companion object {
        val START: Instant = Instant.parse("2026-08-29T08:00:00Z")
        val END: Instant = Instant.parse("2026-08-29T08:15:00Z")
        val window = ProbeTimeWindow(
            startInclusive = Instant.parse("2026-08-29T00:00:00Z"),
            endExclusive = Instant.parse("2026-08-30T00:00:00Z"),
        )
        val allPermissions: Set<String> = HealthConnectConfiguration.readPermissions
    }
}
