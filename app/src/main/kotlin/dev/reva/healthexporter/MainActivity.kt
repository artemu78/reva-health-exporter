package dev.reva.healthexporter

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        private const val DIAGNOSTIC_STATE_KEY = "diagnostic_screen_state"
        private const val SNAPSHOT_FILE_NAME = "reva-health-diagnostic.json"

        internal var driveAuthorizationGatewayFactory:
            (ComponentActivity, (DriveAuthorizationResult) -> Unit) -> DriveAuthorizationGateway =
            { activity, complete -> (activity as MainActivity).createGoogleDriveAuthorizationGateway(complete) }

        internal fun resetDriveAuthorizationGatewayFactory() {
            driveAuthorizationGatewayFactory =
                { activity, complete -> (activity as MainActivity).createGoogleDriveAuthorizationGateway(complete) }
        }

        internal var googleDriveGatewayFactory:
            (ComponentActivity, String?) -> GoogleDriveGateway =
            { activity, accountId -> (activity as MainActivity).createGoogleDriveGateway(accountId) }

        internal fun resetGoogleDriveGatewayFactory() {
            googleDriveGatewayFactory =
                { activity, accountId -> (activity as MainActivity).createGoogleDriveGateway(accountId) }
        }
    }

    private lateinit var permissionLauncher: ActivityResultLauncher<Set<String>>
    private lateinit var documentLauncher: ActivityResultLauncher<String>
    private lateinit var driveResolutionLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var driveAuthorizationCoordinator: DriveAuthorizationCoordinator
    private var googleDriveAuthorizationGateway: GoogleDriveAuthorizationGateway? = null
    private var currentPermissions: PermissionSummary? = null
    private var lastGrantedMetrics: Set<HealthMetric>? = null
    private var persistentNotice = PermissionNotice.NONE
    private var refreshJob: Job? = null
    private var diagnosticJob: Job? = null
    private var healthConnectClient: HealthConnectClient? = null
    private var grantedReadPermissions: Set<String> = emptySet()
    private var backgroundReadSupport: BackgroundReadSupport = BackgroundReadSupport.UNSUPPORTED
    private var backgroundSummary: BackgroundReadSummary? = null
    private val probeStore by lazy { SharedPreferencesBackgroundProbeStore(this) }
    private var lastValidDiagnosticState: DiagnosticScreenState? = null
    private var lastDiagnosticResult: DiagnosticProbeResult? = null
    private val diagnosticPresenter by lazy {
        DiagnosticResultsPresenter(
            DiagnosticProbeRunner { window, permissions ->
                val client = checkNotNull(healthConnectClient) { "Health Connect is not ready" }
                HealthRecordProbe(client).probe(window, permissions).also { lastDiagnosticResult = it }
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<TextView>(R.id.app_version).text = getString(R.string.app_version, BuildConfig.VERSION_NAME)
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
        documentLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { destination -> exportSnapshot(destination) }
        driveResolutionLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result -> googleDriveAuthorizationGateway?.completeResolution(result.resultCode, result.data) }
        val driveGateway = driveAuthorizationGatewayFactory(this) { result ->
            driveAuthorizationCoordinator.complete(result)
        }
        googleDriveAuthorizationGateway = driveGateway as? GoogleDriveAuthorizationGateway
        driveAuthorizationCoordinator = DriveAuthorizationCoordinator(
            gateway = driveGateway,
            onStateChanged = ::renderDriveAuthorization,
        )
        findViewById<Button>(R.id.drive_connect).setOnClickListener {
            driveAuthorizationCoordinator.connect()
        }
        findViewById<Button>(R.id.drive_reconnect).setOnClickListener {
            driveAuthorizationCoordinator.reconnect()
        }
        findViewById<Button>(R.id.drive_disconnect).setOnClickListener {
            driveAuthorizationCoordinator.disconnect()
        }
        findViewById<Button>(R.id.drive_export_now).setOnClickListener {
            triggerDriveExport()
        }
        renderDriveAuthorization(driveAuthorizationCoordinator.state)
        findViewById<Button>(R.id.diagnostic_export).setOnClickListener {
            if (lastDiagnosticResult != null) {
                documentLauncher.launch(SNAPSHOT_FILE_NAME)
            }
        }
        findViewById<Button>(R.id.background_probe_trigger).setOnClickListener {
            triggerBackgroundProbe()
        }
        renderBackgroundAccess()
    }

    fun renderDriveAuthorization(state: DriveAuthorizationState) {
        findViewById<TextView>(R.id.drive_authorization_status).text = when (state) {
            DriveAuthorizationState.Disconnected -> getString(R.string.drive_disconnected)
            DriveAuthorizationState.Connecting -> getString(R.string.drive_connecting)
            is DriveAuthorizationState.Connected -> getString(R.string.drive_connected)
            DriveAuthorizationState.Disconnecting -> getString(R.string.drive_disconnecting)
            DriveAuthorizationState.UserActionRequired -> getString(R.string.drive_user_action_required)
        }
        findViewById<Button>(R.id.drive_connect).visibility =
            if (state == DriveAuthorizationState.Disconnected) View.VISIBLE else View.GONE
        val connected = state is DriveAuthorizationState.Connected ||
            state == DriveAuthorizationState.UserActionRequired
        findViewById<Button>(R.id.drive_reconnect).visibility = if (connected) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.drive_disconnect).visibility = if (connected) View.VISIBLE else View.GONE
        val isDriveConnected = state is DriveAuthorizationState.Connected
        findViewById<Button>(R.id.drive_export_now).visibility = if (isDriveConnected) View.VISIBLE else View.GONE

        if (state is DriveAuthorizationState.Connected) {
            ExportScheduler.schedulePeriodicExport(this)
        } else if (state == DriveAuthorizationState.Disconnected) {
            ExportScheduler.cancelPeriodicExport(this)
        }
        renderExportStatus()
    }

    private fun triggerDriveExport() {
        val client = healthConnectClient
        if (client == null) {
            findViewById<TextView>(R.id.drive_export_status).text =
                getString(R.string.drive_export_health_connect_not_ready)
            return
        }
        val state = driveAuthorizationCoordinator.state
        if (state !is DriveAuthorizationState.Connected) {
            findViewById<TextView>(R.id.drive_export_status).text =
                getString(R.string.drive_export_not_connected)
            return
        }

        findViewById<TextView>(R.id.drive_export_status).text = getString(R.string.drive_export_status_exporting)
        findViewById<Button>(R.id.drive_export_now).isEnabled = false

        ExportWorker.destinationFactory = {
            val gateway = googleDriveGatewayFactory(this, state.accountId)
            GoogleDriveDestination(driveGateway = gateway)
        }
        ExportWorker.clientFactory = { client }

        val workId = ExportScheduler.triggerImmediateExport(this, androidx.work.ExistingWorkPolicy.REPLACE)
        androidx.work.WorkManager.getInstance(this)
            .getWorkInfoByIdLiveData(workId)
            .observe(this) { workInfo ->
                if (workInfo != null && workInfo.state.isFinished) {
                    findViewById<Button>(R.id.drive_export_now).isEnabled = true
                    val outcomeName = workInfo.outputData.getString(ExportWorker.KEY_OUTCOME)
                    if (outcomeName == ExportOutcome.USER_ACTION_REQUIRED.name) {
                        driveAuthorizationCoordinator.observeAuthorizationRequired()
                    }
                    renderExportStatus()
                }
            }
    }

    private fun renderExportStatus() {
        val statusView = findViewById<TextView>(R.id.drive_export_status)
        val stateStore = SharedPreferencesExportStateStore(this)
        val lastSummary = stateStore.getLastExecutionSummary()
        if (lastSummary != null) {
            statusView.text = when (lastSummary.outcome) {
                ExportOutcome.SUCCESS -> getString(
                    R.string.drive_export_status_success,
                    lastSummary.batchId.orEmpty(),
                    lastSummary.recordCount,
                )
                ExportOutcome.NOTHING_TO_EXPORT -> getString(R.string.drive_export_status_nothing)
                ExportOutcome.RETRYABLE_FAILURE,
                ExportOutcome.TERMINAL_FAILURE,
                ExportOutcome.USER_ACTION_REQUIRED,
                -> getString(R.string.drive_export_status_failure, lastSummary.message)
            }
        } else {
            val state = driveAuthorizationCoordinator.state
            if (state is DriveAuthorizationState.Connected) {
                statusView.text = getString(R.string.drive_export_status_periodic_scheduled)
            } else {
                statusView.text = ""
            }
        }
    }

    private fun createGoogleDriveGateway(accountId: String?): GoogleDriveGateway {
        return HttpGoogleDriveGateway(
            accountId = accountId,
            tokenProvider = { googleDriveAuthorizationGateway?.getAccessToken() },
        )
    }

    private fun createGoogleDriveAuthorizationGateway(
        complete: (DriveAuthorizationResult) -> Unit,
    ): GoogleDriveAuthorizationGateway = GoogleDriveAuthorizationGateway(
        activity = this,
        resolutionLauncher = driveResolutionLauncher,
        onComplete = complete,
    )

    override fun onResume() {
        super.onResume()
        refreshHealthConnectState()
        renderExportStatus()
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
        findViewById<Button>(R.id.diagnostic_export).isEnabled =
            lastDiagnosticResult != null && state.phase != DiagnosticScreenPhase.LOADING
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
            val featureStatus = try {
                client.features.getFeatureStatus(
                    androidx.health.connect.client.HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND,
                )
            } catch (_: Exception) {
                androidx.health.connect.client.HealthConnectFeatures.FEATURE_STATUS_UNAVAILABLE
            }
            backgroundReadSupport = classifyBackgroundReadSupport(featureStatus)
            grantedReadPermissions = client.permissionController.getGrantedPermissions()
            backgroundSummary = summarizeBackgroundRead(
                support = backgroundReadSupport,
                grantedPermissions = grantedReadPermissions,
            )
            val grantedMetrics = metricsFor(grantedReadPermissions)
            showPermissions(grantedMetrics)
            renderBackgroundAccess()
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
        val missing = currentPermissions?.let(::permissionsToRequest).orEmpty().toMutableSet()
        val bgMissing = backgroundSummary?.let(::backgroundPermissionsToRequest).orEmpty()
        missing.addAll(bgMissing)
        if (missing.isNotEmpty()) permissionLauncher.launch(missing)
    }

    private fun renderBackgroundAccess() {
        val statusView = findViewById<TextView>(R.id.background_read_status)
        val triggerButton = findViewById<Button>(R.id.background_probe_trigger)
        val resultView = findViewById<TextView>(R.id.background_probe_result)

        when (backgroundReadSupport) {
            BackgroundReadSupport.UNSUPPORTED -> {
                statusView.text = getString(R.string.background_read_unsupported)
            }
            BackgroundReadSupport.AVAILABLE -> {
                val hasBgPerm = backgroundSummary?.hasBackgroundPermission == true
                val permText = if (hasBgPerm) "Granted" else "Missing"
                statusView.text = "${getString(R.string.background_read_supported)} · Permission: $permText"
            }
        }

        val lastSummary = probeStore.loadSummary()
        if (lastSummary != null) {
            resultView.text = formatBackgroundProbeSummary(lastSummary)
        } else {
            resultView.text = getString(R.string.background_probe_no_run)
        }
        triggerButton.isEnabled = true
    }

    private fun triggerBackgroundProbe() {
        val request = androidx.work.OneTimeWorkRequestBuilder<BackgroundProbeWorker>().build()
        androidx.work.WorkManager.getInstance(this).enqueueUniqueWork(
            BackgroundProbeWorker.WORK_NAME,
            androidx.work.ExistingWorkPolicy.REPLACE,
            request,
        )
        findViewById<TextView>(R.id.background_probe_result).text =
            getString(R.string.background_probe_running)

        androidx.work.WorkManager.getInstance(this)
            .getWorkInfoByIdLiveData(request.id)
            .observe(this) { workInfo ->
                if (workInfo != null && workInfo.state.isFinished) {
                    renderBackgroundAccess()
                }
            }
    }

    private fun formatBackgroundProbeSummary(summary: BackgroundReadExecutionSummary): String {
        val outcomeLabel = when (summary.outcome) {
            BackgroundReadOutcome.SUCCESS -> "Success"
            BackgroundReadOutcome.RETRYABLE_FAILURE -> "Failed (retryable)"
            BackgroundReadOutcome.USER_ACTION_REQUIRED -> "User action required"
            BackgroundReadOutcome.UNSUPPORTED -> "Unsupported"
        }
        val timestamp = summary.executionTimestamp?.let {
            java.time.format.DateTimeFormatter.ISO_INSTANT.format(it)
        } ?: "Unknown time"
        val origins = if (summary.dataOrigins.isEmpty()) "No origins" else summary.dataOrigins.sorted().joinToString()
        return "Last background probe: $outcomeLabel\nRecords: ${summary.totalRecords} (${summary.readTypesCount} types) · Origins: $origins\nTime: $timestamp\nDetails: ${summary.message}"
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

    private fun exportSnapshot(destination: Uri?) {
        val result = lastDiagnosticResult
        val exportResult = if (result == null) {
            DocumentExportResult.DestinationUnavailable
        } else {
            val snapshot = diagnosticSnapshot(
                result = result,
                appVersion = BuildConfig.VERSION_NAME,
                androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                grantedMetrics = metricsFor(grantedReadPermissions),
            )
            val content = DiagnosticSnapshotSerializer().serialize(snapshot)
            DiagnosticDocumentExporter(
                DocumentOutput { uri -> contentResolver.openOutputStream(Uri.parse(uri), "wt") },
            ).export(destination?.toString(), content)
        }
        findViewById<TextView>(R.id.diagnostic_export_status).text = when (exportResult) {
            DocumentExportResult.Success -> getString(R.string.diagnostic_export_success)
            DocumentExportResult.Cancelled -> getString(R.string.diagnostic_export_cancelled)
            DocumentExportResult.DestinationUnavailable ->
                getString(R.string.diagnostic_export_destination_unavailable)
            DocumentExportResult.WriteFailed -> getString(R.string.diagnostic_export_write_failed)
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

}

sealed interface DiagnosticUiAction {
    data object Refresh : DiagnosticUiAction
    data class SelectWindow(val window: DiagnosticTimeWindow) : DiagnosticUiAction
    data class RevealPreview(val metric: HealthMetric) : DiagnosticUiAction
}
