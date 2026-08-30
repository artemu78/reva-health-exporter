package dev.reva.healthexporter

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var permissionLauncher: ActivityResultLauncher<Set<String>>
    private var currentPermissions: PermissionSummary? = null
    private var lastGrantedMetrics: Set<HealthMetric>? = null
    private var persistentNotice = PermissionNotice.NONE
    private var refreshJob: Job? = null
    private var diagnosticJob: Job? = null
    private var healthConnectClient: HealthConnectClient? = null
    private var grantedReadPermissions: Set<String> = emptySet()
    private var lastValidDiagnosticState: DiagnosticScreenState? = null
    private val diagnosticPresenter by lazy {
        DiagnosticResultsPresenter(
            DiagnosticProbeRunner { window, permissions ->
                val client = checkNotNull(healthConnectClient) { "Health Connect is not ready" }
                HealthRecordProbe(client).probe(window, permissions)
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        restoredDiagnosticState(savedInstanceState)?.let { restored ->
            diagnosticPresenter.restore(restored)
            renderDiagnostic(restored)
        } ?: renderDiagnostic(diagnosticPresenter.state)
        permissionLauncher = registerForActivityResult(
            PermissionController.createRequestPermissionResultContract(),
        ) { grantedPermissions ->
            grantedReadPermissions = grantedPermissions
            val grantedMetrics = metricsFor(grantedPermissions)
            persistentNotice = if (grantedMetrics.containsAll(HealthConnectConfiguration.selectedMetrics)) {
                PermissionNotice.NONE
            } else {
                PermissionNotice.DENIED
            }
            showPermissions(grantedMetrics)
            refreshDiagnostics()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHealthConnectState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        lastValidDiagnosticState?.let { outState.putSerializable(DIAGNOSTIC_STATE_KEY, it) }
        super.onSaveInstanceState(outState)
    }

    fun render(
        state: HealthConnectScreenState,
        onAction: (HealthConnectAction) -> Unit = ::performAction,
    ) {
        findViewById<TextView>(R.id.health_connect_status).text = state.message
        renderOptionalText(R.id.granted_permissions, state.grantedPermissions)
        renderOptionalText(R.id.missing_permissions, state.missingPermissions)
        findViewById<Button>(R.id.health_connect_action).apply {
            visibility = if (state.action == null) View.GONE else View.VISIBLE
            text = state.actionLabel.orEmpty()
            setOnClickListener(
                state.action?.let { action -> View.OnClickListener { onAction(action) } },
            )
        }
    }

    fun renderDiagnostic(
        state: DiagnosticScreenState,
        onAction: (DiagnosticUiAction) -> Unit = ::performDiagnosticAction,
    ) {
        if (state.phase != DiagnosticScreenPhase.LOADING && state.phase != DiagnosticScreenPhase.ERROR) {
            lastValidDiagnosticState = state
        }
        findViewById<TextView>(R.id.diagnostic_status).text = state.message
        findViewById<ProgressBar>(R.id.diagnostic_progress).visibility =
            if (state.phase == DiagnosticScreenPhase.LOADING) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.diagnostic_refresh).apply {
            isEnabled = state.phase != DiagnosticScreenPhase.LOADING &&
                (state.phase != DiagnosticScreenPhase.ERROR || state.canRetry)
            setOnClickListener { onAction(DiagnosticUiAction.Refresh) }
        }
        findViewById<RadioGroup>(R.id.diagnostic_time_window).apply {
            setOnCheckedChangeListener(null)
            check(
                if (state.selectedWindow == DiagnosticTimeWindow.TWENTY_FOUR_HOURS) {
                    R.id.window_twenty_four_hours
                } else {
                    R.id.window_seven_days
                },
            )
            setOnCheckedChangeListener { _, checkedId ->
                val selected = if (checkedId == R.id.window_seven_days) {
                    DiagnosticTimeWindow.SEVEN_DAYS
                } else {
                    DiagnosticTimeWindow.TWENTY_FOUR_HOURS
                }
                if (selected != state.selectedWindow) {
                    onAction(DiagnosticUiAction.SelectWindow(selected))
                }
            }
        }

        findViewById<LinearLayout>(R.id.diagnostic_results).apply {
            removeAllViews()
            state.rows.forEach { row -> addView(createDiagnosticRow(row, onAction)) }
        }
    }

    private fun renderOptionalText(viewId: Int, value: String?) {
        findViewById<TextView>(viewId).apply {
            visibility = if (value == null) View.GONE else View.VISIBLE
            text = value.orEmpty()
        }
    }

    private fun refreshHealthConnectState() {
        val availability = classifyProviderAvailability(
            HealthConnectClient.getSdkStatus(
                this,
                HealthConnectConfiguration.PROVIDER_PACKAGE_NAME,
            ),
        )
        render(healthConnectScreenState(availability))
        if (availability != ProviderAvailability.AVAILABLE) {
            diagnosticJob?.cancel()
            if (lastValidDiagnosticState == null) {
                renderDiagnostic(
                    DiagnosticScreenState(
                        phase = DiagnosticScreenPhase.ERROR,
                        selectedWindow = diagnosticPresenter.state.selectedWindow,
                        message = "Health Connect must be available before diagnostics can run.",
                        canRetry = false,
                    ),
                )
            }
            return
        }

        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            val client = HealthConnectClient.getOrCreate(this@MainActivity)
            healthConnectClient = client
            grantedReadPermissions = client.permissionController.getGrantedPermissions()
            val grantedMetrics = metricsFor(grantedReadPermissions)
            showPermissions(grantedMetrics)
            refreshDiagnostics()
        }
    }

    private fun showPermissions(grantedMetrics: Set<HealthMetric>) {
        val previouslyGranted = lastGrantedMetrics.orEmpty()
        if (
            persistentNotice != PermissionNotice.DENIED &&
            previouslyGranted.containsAll(HealthConnectConfiguration.selectedMetrics) &&
            !grantedMetrics.containsAll(HealthConnectConfiguration.selectedMetrics)
        ) {
            persistentNotice = PermissionNotice.REVOKED
        }
        if (grantedMetrics.containsAll(HealthConnectConfiguration.selectedMetrics)) {
            persistentNotice = PermissionNotice.NONE
        }

        currentPermissions = summarizePermissions(
            required = HealthConnectConfiguration.selectedMetrics,
            granted = grantedMetrics,
            requestDenied = persistentNotice == PermissionNotice.DENIED,
            previouslyGranted = if (persistentNotice == PermissionNotice.REVOKED) {
                HealthConnectConfiguration.selectedMetrics
            } else {
                previouslyGranted
            },
        )
        lastGrantedMetrics = grantedMetrics
        render(
            healthConnectScreenState(
                ProviderAvailability.AVAILABLE,
                currentPermissions,
            ),
        )
    }

    private fun requestMissingPermissions() {
        val missing = currentPermissions?.let(::permissionsToRequest).orEmpty()
        if (missing.isNotEmpty()) permissionLauncher.launch(missing)
    }

    private fun performAction(action: HealthConnectAction) {
        when (action) {
            HealthConnectAction.INSTALL_PROVIDER,
            HealthConnectAction.UPDATE_PROVIDER,
            -> openProviderStorePage()
            HealthConnectAction.REQUEST_PERMISSIONS -> requestMissingPermissions()
        }
    }

    private fun performDiagnosticAction(action: DiagnosticUiAction) {
        when (action) {
            DiagnosticUiAction.Refresh -> refreshDiagnostics()
            is DiagnosticUiAction.SelectWindow -> refreshDiagnostics(action.window)
            is DiagnosticUiAction.RevealPreview ->
                renderDiagnostic(diagnosticPresenter.revealPreview(action.metric))
        }
    }

    private fun refreshDiagnostics(window: DiagnosticTimeWindow? = null) {
        if (healthConnectClient == null) return
        diagnosticJob?.cancel()
        diagnosticJob = lifecycleScope.launch {
            if (window == null) {
                diagnosticPresenter.refresh(grantedReadPermissions, ::renderDiagnostic)
            } else {
                diagnosticPresenter.selectWindow(window, grantedReadPermissions, ::renderDiagnostic)
            }
        }
    }

    private fun createDiagnosticRow(
        row: DiagnosticMetricRow,
        onAction: (DiagnosticUiAction) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(16), 0, dp(16))
        contentDescription = "Summary for ${row.metric.displayName}"
        isFocusable = true

        addView(textView(row.metric.displayName, heading = true))
        addView(textView("Status: ${row.status}"))
        addView(textView("Count: ${row.count}"))
        addView(textView("Time coverage: ${row.coverage}"))
        addView(textView("Origins: ${row.origins}"))

        if (row.previewVisible) {
            addView(
                textView(row.previewLines.joinToString(separator = "\n")).apply {
                    contentDescription = "Limited preview for ${row.metric.displayName}"
                },
            )
        } else if (row.availablePreviewLines.isNotEmpty()) {
            addView(
                Button(this@MainActivity).apply {
                    text = "Show limited preview"
                    contentDescription = "Show limited preview for ${row.metric.displayName}"
                    setOnClickListener {
                        onAction(DiagnosticUiAction.RevealPreview(row.metric))
                    }
                },
            )
        }
    }

    private fun textView(value: String, heading: Boolean = false): TextView = TextView(this).apply {
        text = value
        if (heading) {
            textSize = 18f
            isAccessibilityHeading = true
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun restoredDiagnosticState(savedInstanceState: Bundle?): DiagnosticScreenState? =
        savedInstanceState?.getSerializable(DIAGNOSTIC_STATE_KEY) as? DiagnosticScreenState

    private fun metricsFor(permissions: Set<String>): Set<HealthMetric> =
        HealthConnectConfiguration.permissionByMetric
            .filterValues(permissions::contains)
            .keys

    private fun openProviderStorePage() {
        val provider = HealthConnectConfiguration.PROVIDER_PACKAGE_NAME
        val marketUri = Uri.parse("market://details?id=$provider&url=healthconnect%3A%2F%2Fonboarding")
        val marketIntent = Intent(Intent.ACTION_VIEW, marketUri).apply {
            setPackage("com.android.vending")
            putExtra("overlay", true)
            putExtra("callerId", packageName)
        }
        try {
            startActivity(marketIntent)
        } catch (_: ActivityNotFoundException) {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$provider"),
                ),
            )
        }
    }

    private companion object {
        const val DIAGNOSTIC_STATE_KEY = "diagnostic_screen_state"
    }
}

sealed interface DiagnosticUiAction {
    data object Refresh : DiagnosticUiAction
    data class SelectWindow(val window: DiagnosticTimeWindow) : DiagnosticUiAction
    data class RevealPreview(val metric: HealthMetric) : DiagnosticUiAction
}
