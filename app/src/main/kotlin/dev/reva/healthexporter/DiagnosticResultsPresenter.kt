package dev.reva.healthexporter

import java.io.Serializable
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CancellationException

enum class DiagnosticTimeWindow(val days: Int, val displayName: String) {
    TWENTY_FOUR_HOURS(1, "Last 24 hours"),
    SEVEN_DAYS(7, "Last 7 days"),
}

enum class DiagnosticScreenPhase {
    LOADING,
    EMPTY,
    SUCCESS,
    PERMISSION_DENIED,
    ERROR,
}

data class DiagnosticMetricRow(
    val metric: HealthMetric,
    val status: String,
    val count: String,
    val coverage: String,
    val origins: String,
    val availablePreviewLines: List<String> = emptyList(),
    val previewVisible: Boolean = false,
) : Serializable {
    val previewLines: List<String>
        get() = if (previewVisible) availablePreviewLines else emptyList()
}

data class DiagnosticScreenState(
    val phase: DiagnosticScreenPhase,
    val selectedWindow: DiagnosticTimeWindow,
    val message: String,
    val rows: List<DiagnosticMetricRow> = emptyList(),
    val canRetry: Boolean = false,
) : Serializable

fun interface DiagnosticProbeRunner {
    suspend fun probe(
        window: ProbeTimeWindow,
        grantedPermissions: Set<String>,
    ): DiagnosticProbeResult
}

interface DiagnosticClock {
    fun now(zoneId: ZoneId): ZonedDateTime
}

object SystemDiagnosticClock : DiagnosticClock {
    override fun now(zoneId: ZoneId): ZonedDateTime = ZonedDateTime.now(zoneId)
}

class DiagnosticResultsPresenter(
    private val runner: DiagnosticProbeRunner,
    private val clock: DiagnosticClock = SystemDiagnosticClock,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    var state = DiagnosticScreenState(
        phase = DiagnosticScreenPhase.LOADING,
        selectedWindow = DiagnosticTimeWindow.TWENTY_FOUR_HOURS,
        message = "Preparing diagnostic results…",
    )
        private set

    private var lastValidState: DiagnosticScreenState? = null

    suspend fun refresh(
        grantedPermissions: Set<String>,
        onStateChange: (DiagnosticScreenState) -> Unit = {},
    ) {
        state = DiagnosticScreenState(
            phase = DiagnosticScreenPhase.LOADING,
            selectedWindow = state.selectedWindow,
            message = "Loading ${state.selectedWindow.displayName.lowercase()}…",
            rows = lastValidState?.rows.orEmpty(),
        )
        onStateChange(state)

        val now = clock.now(zoneId)
        val window = ProbeTimeWindow.previousLocalDays(now, state.selectedWindow.days)
        state = try {
            runner.probe(window, grantedPermissions).toScreenState(state.selectedWindow)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            DiagnosticScreenState(
                phase = DiagnosticScreenPhase.ERROR,
                selectedWindow = state.selectedWindow,
                message = "Health Connect could not be read. Try refreshing the diagnostic.",
                rows = lastValidState?.rows.orEmpty(),
                canRetry = true,
            )
        }
        if (state.phase != DiagnosticScreenPhase.ERROR) lastValidState = state
        onStateChange(state)
    }

    suspend fun selectWindow(
        selectedWindow: DiagnosticTimeWindow,
        grantedPermissions: Set<String>,
        onStateChange: (DiagnosticScreenState) -> Unit = {},
    ) {
        state = state.copy(selectedWindow = selectedWindow)
        refresh(grantedPermissions, onStateChange)
    }

    fun revealPreview(metric: HealthMetric): DiagnosticScreenState {
        state = state.copy(
            rows = state.rows.map { row ->
                if (row.metric == metric) row.copy(previewVisible = true) else row
            },
        )
        if (state.phase != DiagnosticScreenPhase.ERROR) lastValidState = state
        return state
    }

    fun restore(restoredState: DiagnosticScreenState) {
        state = restoredState
        if (restoredState.phase != DiagnosticScreenPhase.LOADING &&
            restoredState.phase != DiagnosticScreenPhase.ERROR
        ) {
            lastValidState = restoredState
        }
    }

    private fun DiagnosticProbeResult.toScreenState(
        selectedWindow: DiagnosticTimeWindow,
    ): DiagnosticScreenState {
        val orderedSummaries = HealthMetric.entries.map { metric ->
            summaries[metric] ?: MetricProbeSummary(metric, MetricProbeStatus.FAILED)
        }
        val phase = when {
            orderedSummaries.all { it.status == MetricProbeStatus.FAILED } -> DiagnosticScreenPhase.ERROR
            orderedSummaries.any { it.status == MetricProbeStatus.PERMISSION_MISSING } ->
                DiagnosticScreenPhase.PERMISSION_DENIED
            orderedSummaries.all {
                it.status == MetricProbeStatus.EMPTY || it.status == MetricProbeStatus.UNSUPPORTED
            } -> DiagnosticScreenPhase.EMPTY
            else -> DiagnosticScreenPhase.SUCCESS
        }
        return DiagnosticScreenState(
            phase = phase,
            selectedWindow = selectedWindow,
            message = phase.message(),
            rows = orderedSummaries.map { it.toRow() },
            canRetry = phase == DiagnosticScreenPhase.ERROR,
        )
    }

    private fun MetricProbeSummary.toRow(): DiagnosticMetricRow = DiagnosticMetricRow(
        metric = metric,
        status = status.displayName(),
        count = "$count ${if (count == 1) "record" else "records"}",
        coverage = if (oldestTimestamp == null || newestTimestamp == null) {
            "No time coverage"
        } else {
            "${DateTimeFormatter.ISO_INSTANT.format(oldestTimestamp)} → " +
                DateTimeFormatter.ISO_INSTANT.format(newestTimestamp)
        },
        origins = if (dataOrigins.isEmpty()) {
            "None reported"
        } else {
            dataOrigins.sorted().joinToString()
        },
        availablePreviewLines = previews.take(MAX_PREVIEW_RECORDS).map { preview ->
            val origin = preview.dataOrigin ?: "Origin not reported"
            "${DateTimeFormatter.ISO_INSTANT.format(preview.startTimestamp)} → " +
                "${DateTimeFormatter.ISO_INSTANT.format(preview.endTimestamp)} · $origin"
        },
    )

    private fun MetricProbeStatus.displayName(): String = when (this) {
        MetricProbeStatus.POPULATED -> "Available"
        MetricProbeStatus.EMPTY -> "Empty"
        MetricProbeStatus.PERMISSION_MISSING -> "Permission required"
        MetricProbeStatus.UNSUPPORTED -> "Unsupported"
        MetricProbeStatus.FAILED -> "Read failed"
    }

    private fun DiagnosticScreenPhase.message(): String = when (this) {
        DiagnosticScreenPhase.LOADING -> "Loading diagnostic results…"
        DiagnosticScreenPhase.EMPTY -> "No accessible records were found in this time window."
        DiagnosticScreenPhase.SUCCESS -> "Diagnostic results are ready."
        DiagnosticScreenPhase.PERMISSION_DENIED ->
            "Some record types need permission. Available results are still shown."
        DiagnosticScreenPhase.ERROR -> "Health Connect could not be read. Try refreshing the diagnostic."
    }

    private companion object {
        const val MAX_PREVIEW_RECORDS = 3
    }
}
