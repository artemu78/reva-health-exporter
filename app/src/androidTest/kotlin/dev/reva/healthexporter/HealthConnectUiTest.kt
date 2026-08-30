package dev.reva.healthexporter

import android.widget.Button
import android.widget.TextView
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HealthConnectUiTest {
    private val selectedMetrics = HealthMetric.entries.toSet()

    @Test
    fun unavailable_provider_shows_explanation_and_install_action() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.render(healthConnectScreenState(ProviderAvailability.UNAVAILABLE))

                assertEquals(
                    "Health Connect is not installed. Install it to inspect health data.",
                    activity.findViewById<TextView>(R.id.health_connect_status).text.toString(),
                )
                assertEquals(
                    "Install Health Connect",
                    activity.findViewById<Button>(R.id.health_connect_action).text.toString(),
                )
            }
        }
    }

    @Test
    fun update_required_provider_shows_explanation_and_update_action() {
        assertScreen(
            state = healthConnectScreenState(ProviderAvailability.UPDATE_REQUIRED),
            message = "Health Connect must be updated before health data can be inspected.",
            action = "Update Health Connect",
        )
    }

    @Test
    fun install_button_dispatches_the_install_provider_action() {
        assertActionDispatched(
            healthConnectScreenState(ProviderAvailability.UNAVAILABLE),
            HealthConnectAction.INSTALL_PROVIDER,
        )
    }

    @Test
    fun update_button_dispatches_the_update_provider_action() {
        assertActionDispatched(
            healthConnectScreenState(ProviderAvailability.UPDATE_REQUIRED),
            HealthConnectAction.UPDATE_PROVIDER,
        )
    }

    @Test
    fun grant_button_dispatches_the_permission_request_action() {
        val permissionState = summarizePermissions(selectedMetrics, granted = emptySet())
        assertActionDispatched(
            healthConnectScreenState(ProviderAvailability.AVAILABLE, permissionState),
            HealthConnectAction.REQUEST_PERMISSIONS,
        )
    }

    @Test
    fun no_permissions_shows_granted_missing_and_request_action() {
        val permissionState = summarizePermissions(selectedMetrics, granted = emptySet())

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.render(
                    healthConnectScreenState(ProviderAvailability.AVAILABLE, permissionState),
                )

                assertEquals(
                    "No selected read permissions are granted.",
                    activity.findViewById<TextView>(R.id.health_connect_status).text.toString(),
                )
                assertEquals(
                    "Granted: None",
                    activity.findViewById<TextView>(R.id.granted_permissions).text.toString(),
                )
                assertEquals(
                    "Missing: Steps, Heart rate, Resting heart rate, Sleep, Distance, " +
                        "Total calories burned, Exercise sessions, Oxygen saturation",
                    activity.findViewById<TextView>(R.id.missing_permissions).text.toString(),
                )
                assertEquals(
                    "Grant read permissions",
                    activity.findViewById<Button>(R.id.health_connect_action).text.toString(),
                )
            }
        }
    }

    @Test
    fun partial_permissions_show_granted_missing_and_missing_only_action() {
        val permissionState = summarizePermissions(
            selectedMetrics,
            granted = setOf(HealthMetric.STEPS),
        )

        assertScreen(
            state = healthConnectScreenState(ProviderAvailability.AVAILABLE, permissionState),
            message = "Some selected read permissions are missing.",
            granted = "Granted: Steps",
            missing = "Missing: Heart rate, Resting heart rate, Sleep, Distance, " +
                "Total calories burned, Exercise sessions, Oxygen saturation",
            action = "Grant missing permissions",
        )
    }

    @Test
    fun all_permissions_show_complete_state_without_an_action() {
        val permissionState = summarizePermissions(selectedMetrics, granted = selectedMetrics)

        assertScreen(
            state = healthConnectScreenState(ProviderAvailability.AVAILABLE, permissionState),
            message = "All selected read permissions are granted.",
            granted = "Granted: Steps, Heart rate, Resting heart rate, Sleep, Distance, " +
                "Total calories burned, Exercise sessions, Oxygen saturation",
            missing = "Missing: None",
            action = null,
        )
    }

    @Test
    fun denial_explains_the_limitation_and_keeps_retry_available() {
        val permissionState = summarizePermissions(
            selectedMetrics,
            granted = emptySet(),
            requestDenied = true,
        )

        assertScreen(
            state = healthConnectScreenState(ProviderAvailability.AVAILABLE, permissionState),
            message = "Permission request was denied. The app remains usable, but diagnostics are limited.",
            granted = "Granted: None",
            action = "Try again",
        )
    }

    @Test
    fun revocation_explains_the_loss_and_offers_grant_again() {
        val permissionState = summarizePermissions(
            required = selectedMetrics,
            granted = selectedMetrics - HealthMetric.SLEEP,
            previouslyGranted = selectedMetrics,
        )

        assertScreen(
            state = healthConnectScreenState(ProviderAvailability.AVAILABLE, permissionState),
            message = "Health Connect permissions were revoked. Grant access again to continue diagnostics.",
            missing = "Missing: Sleep",
            action = "Grant again",
        )
    }

    private fun assertScreen(
        state: HealthConnectScreenState,
        message: String,
        granted: String? = null,
        missing: String? = null,
        action: String?,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.render(state)

                assertEquals(
                    message,
                    activity.findViewById<TextView>(R.id.health_connect_status).text.toString(),
                )
                granted?.let {
                    assertEquals(
                        it,
                        activity.findViewById<TextView>(R.id.granted_permissions).text.toString(),
                    )
                }
                missing?.let {
                    assertEquals(
                        it,
                        activity.findViewById<TextView>(R.id.missing_permissions).text.toString(),
                    )
                }
                val actionView = activity.findViewById<Button>(R.id.health_connect_action)
                if (action == null) {
                    assertEquals(View.GONE, actionView.visibility)
                } else {
                    assertEquals(View.VISIBLE, actionView.visibility)
                    assertEquals(action, actionView.text.toString())
                }
            }
        }
    }

    private fun assertActionDispatched(
        state: HealthConnectScreenState,
        expected: HealthConnectAction,
    ) {
        var dispatched: HealthConnectAction? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.render(state) { dispatched = it }
                activity.findViewById<Button>(R.id.health_connect_action).performClick()
                assertEquals(expected, dispatched)
            }
        }
    }
}
