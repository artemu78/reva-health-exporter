package dev.reva.healthexporter

import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticResultsPresenterTest {
    private val now = ZonedDateTime.parse("2026-08-30T12:00:00+03:00[Europe/Moscow]")
    private val allPermissions = HealthConnectConfiguration.readPermissions

    @Test
    fun `refresh represents loading then success with every candidate summary`() = runBlocking {
        val result = diagnosticResult(
            MetricProbeStatus.POPULATED,
            count = 2,
            previews = listOf(preview("2026-08-30T08:00:00Z")),
        )
        val emitted = mutableListOf<DiagnosticScreenState>()
        val presenter = presenterReturning(result)

        presenter.refresh(allPermissions, emitted::add)

        assertEquals(listOf(DiagnosticScreenPhase.LOADING, DiagnosticScreenPhase.SUCCESS), emitted.map { it.phase })
        assertEquals(HealthMetric.entries.toList(), presenter.state.rows.map { it.metric })
        presenter.state.rows.forEach { row ->
            assertEquals("2 records", row.count)
            assertEquals("com.example.mi.fitness", row.origins)
            assertTrue(row.coverage.contains("2026-08-30"))
        }
    }

    @Test
    fun `all empty candidates produce the empty screen state`() = runBlocking {
        val presenter = presenterReturning(diagnosticResult(MetricProbeStatus.EMPTY))

        presenter.refresh(allPermissions)

        assertEquals(DiagnosticScreenPhase.EMPTY, presenter.state.phase)
        assertEquals(HealthMetric.entries.size, presenter.state.rows.size)
        assertTrue(presenter.state.rows.all { it.status == "Empty" && it.count == "0 records" })
    }

    @Test
    fun `missing permission produces a permission denied state without hiding other candidates`() = runBlocking {
        val summaries = HealthMetric.entries.associateWith { metric ->
            MetricProbeSummary(
                metric = metric,
                status = if (metric == HealthMetric.SLEEP) {
                    MetricProbeStatus.PERMISSION_MISSING
                } else {
                    MetricProbeStatus.EMPTY
                },
            )
        }
        val presenter = presenterReturning(DiagnosticProbeResult(window(), summaries))

        presenter.refresh(allPermissions - HealthConnectConfiguration.permissionByMetric.getValue(HealthMetric.SLEEP))

        assertEquals(DiagnosticScreenPhase.PERMISSION_DENIED, presenter.state.phase)
        assertEquals("Permission required", presenter.state.rows.single { it.metric == HealthMetric.SLEEP }.status)
        assertEquals(HealthMetric.entries.size, presenter.state.rows.size)
    }

    @Test
    fun `fatal error is recoverable and retains the last valid results`() = runBlocking {
        var shouldFail = false
        val runner = DiagnosticProbeRunner { _, _ ->
            if (shouldFail) throw IOException("provider unavailable")
            diagnosticResult(MetricProbeStatus.EMPTY)
        }
        val presenter = DiagnosticResultsPresenter(runner, FixedDiagnosticClock(now))
        presenter.refresh(allPermissions)
        shouldFail = true

        presenter.refresh(allPermissions)

        assertEquals(DiagnosticScreenPhase.ERROR, presenter.state.phase)
        assertEquals(HealthMetric.entries.size, presenter.state.rows.size)
        assertTrue(presenter.state.canRetry)

        shouldFail = false
        presenter.refresh(allPermissions)
        assertEquals(DiagnosticScreenPhase.EMPTY, presenter.state.phase)
    }

    @Test
    fun `all failed candidates produce a recoverable error state`() = runBlocking {
        val presenter = presenterReturning(diagnosticResult(MetricProbeStatus.FAILED))

        presenter.refresh(allPermissions)

        assertEquals(DiagnosticScreenPhase.ERROR, presenter.state.phase)
        assertTrue(presenter.state.canRetry)
        assertEquals(HealthMetric.entries.size, presenter.state.rows.size)
    }

    @Test
    fun `record metadata preview stays hidden until explicit action and remains limited`() = runBlocking {
        val previews = (1..5).map { index -> preview("2026-08-30T0${index}:00:00Z") }
        val presenter = presenterReturning(
            diagnosticResult(MetricProbeStatus.POPULATED, count = 5, previews = previews),
        )
        presenter.refresh(allPermissions)

        val hidden = presenter.state.rows.single { it.metric == HealthMetric.STEPS }
        assertFalse(hidden.previewVisible)
        assertTrue(hidden.previewLines.isEmpty())

        presenter.revealPreview(HealthMetric.STEPS)

        val visible = presenter.state.rows.single { it.metric == HealthMetric.STEPS }
        assertTrue(visible.previewVisible)
        assertEquals(3, visible.previewLines.size)
        assertTrue(visible.previewLines.all { "com.example.mi.fitness" in it })
    }

    @Test
    fun `selecting seven days changes the bounded local-day window`() = runBlocking {
        var capturedWindow: ProbeTimeWindow? = null
        val presenter = DiagnosticResultsPresenter(
            DiagnosticProbeRunner { requestedWindow, _ ->
                capturedWindow = requestedWindow
                diagnosticResult(MetricProbeStatus.EMPTY, requestedWindow)
            },
            FixedDiagnosticClock(now),
        )

        presenter.selectWindow(DiagnosticTimeWindow.SEVEN_DAYS, allPermissions)

        assertEquals(DiagnosticTimeWindow.SEVEN_DAYS, presenter.state.selectedWindow)
        assertEquals(Instant.parse("2026-08-23T09:00:00Z"), capturedWindow?.startInclusive)
        assertEquals(Instant.parse("2026-08-30T09:00:00Z"), capturedWindow?.endExclusive)
    }

    private fun presenterReturning(result: DiagnosticProbeResult) = DiagnosticResultsPresenter(
        DiagnosticProbeRunner { _, _ -> result },
        FixedDiagnosticClock(now),
    )

    private fun diagnosticResult(
        status: MetricProbeStatus,
        window: ProbeTimeWindow = window(),
        count: Int = 0,
        previews: List<MetricRecordPreview> = emptyList(),
    ): DiagnosticProbeResult = DiagnosticProbeResult(
        window = window,
        summaries = HealthMetric.entries.associateWith { metric ->
            MetricProbeSummary(
                metric = metric,
                status = status,
                count = count,
                oldestTimestamp = previews.minOfOrNull(MetricRecordPreview::startTimestamp),
                newestTimestamp = previews.maxOfOrNull(MetricRecordPreview::endTimestamp),
                dataOrigins = if (previews.isEmpty()) emptySet() else setOf("com.example.mi.fitness"),
                previews = previews,
            )
        },
    )

    private fun preview(timestamp: String): MetricRecordPreview {
        val start = Instant.parse(timestamp)
        return MetricRecordPreview(
            startTimestamp = start,
            endTimestamp = start.plusSeconds(300),
            dataOrigin = "com.example.mi.fitness",
        )
    }

    private fun window() = ProbeTimeWindow(
        Instant.parse("2026-08-29T09:00:00Z"),
        Instant.parse("2026-08-30T09:00:00Z"),
    )

    private class FixedDiagnosticClock(private val value: ZonedDateTime) : DiagnosticClock {
        override fun now(zoneId: ZoneId): ZonedDateTime = value.withZoneSameInstant(zoneId)
    }
}
