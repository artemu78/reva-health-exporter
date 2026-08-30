package dev.reva.healthexporter

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        permissionLauncher = registerForActivityResult(
            PermissionController.createRequestPermissionResultContract(),
        ) { grantedPermissions ->
            val grantedMetrics = metricsFor(grantedPermissions)
            persistentNotice = if (grantedMetrics.containsAll(HealthConnectConfiguration.selectedMetrics)) {
                PermissionNotice.NONE
            } else {
                PermissionNotice.DENIED
            }
            showPermissions(grantedMetrics)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHealthConnectState()
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
        if (availability != ProviderAvailability.AVAILABLE) return

        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            val client = HealthConnectClient.getOrCreate(this@MainActivity)
            val grantedMetrics = metricsFor(client.permissionController.getGrantedPermissions())
            showPermissions(grantedMetrics)
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
