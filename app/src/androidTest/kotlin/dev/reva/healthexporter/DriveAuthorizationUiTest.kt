package dev.reva.healthexporter

import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DriveAuthorizationUiTest {
    private class FakeGateway : DriveAuthorizationGateway {
        var launches = 0
        var disconnects = 0
        var onLaunch: (() -> Unit)? = null
        override fun launchAuthorization() {
            launches += 1
            onLaunch?.invoke()
        }
        override fun disconnect(onComplete: (DriveDisconnectionResult) -> Unit) {
            disconnects += 1
            onComplete(DriveDisconnectionResult.Disconnected)
        }
    }

    @After
    fun tearDown() {
        MainActivity.resetDriveAuthorizationGatewayFactory()
        MainActivity.resetGoogleDriveGatewayFactory()
    }

    @Test
    fun authorization_is_launched_only_after_the_user_taps_connect() {
        val gateway = FakeGateway()
        MainActivity.driveAuthorizationGatewayFactory = { _, _ -> gateway }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(0, gateway.launches)
                assertEquals(
                    "Google Drive is not connected.",
                    activity.findViewById<TextView>(R.id.drive_authorization_status).text.toString(),
                )

                activity.findViewById<Button>(R.id.drive_connect).performClick()
                assertEquals(1, gateway.launches)
            }
        }
    }

    @Test
    fun reconnect_and_disconnect_are_explicit_buttons_when_connected() {
        val gateway = FakeGateway()
        MainActivity.driveAuthorizationGatewayFactory = { _, complete ->
            gateway.onLaunch = { complete(DriveAuthorizationResult.Authorized("synthetic-account")) }
            gateway
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<Button>(R.id.drive_connect).performClick()
                activity.findViewById<Button>(R.id.drive_reconnect).performClick()
                activity.findViewById<Button>(R.id.drive_disconnect).performClick()
                assertEquals(2, gateway.launches)
                assertEquals(1, gateway.disconnects)
            }
        }
    }

    @Test
    fun disconnect_failure_keeps_the_activity_open_with_a_recovery_action() {
        lateinit var completeAuthorization: (DriveAuthorizationResult) -> Unit
        val gateway = object : DriveAuthorizationGateway {
            override fun launchAuthorization() {
                completeAuthorization(DriveAuthorizationResult.Authorized(accountId = null))
            }

            override fun disconnect(onComplete: (DriveDisconnectionResult) -> Unit) {
                onComplete(DriveDisconnectionResult.Failed)
            }
        }
        MainActivity.driveAuthorizationGatewayFactory = { _, complete ->
            completeAuthorization = complete
            gateway
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<Button>(R.id.drive_connect).performClick()
                activity.findViewById<Button>(R.id.drive_disconnect).performClick()

                assertEquals(
                    "Google Drive access needs your attention. Reconnect to continue.",
                    activity.findViewById<TextView>(R.id.drive_authorization_status).text.toString(),
                )
            }
        }
    }

    @Test
    fun export_now_button_is_visible_only_when_drive_is_connected() {
        val gateway = FakeGateway()
        MainActivity.driveAuthorizationGatewayFactory = { _, complete ->
            gateway.onLaunch = { complete(DriveAuthorizationResult.Authorized("synthetic-account")) }
            gateway
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(android.view.View.GONE, activity.findViewById<Button>(R.id.drive_export_now).visibility)

                activity.findViewById<Button>(R.id.drive_connect).performClick()
                assertEquals(android.view.View.VISIBLE, activity.findViewById<Button>(R.id.drive_export_now).visibility)
                assertEquals(
                    "Periodic export is scheduled.",
                    activity.findViewById<TextView>(R.id.drive_export_status).text.toString(),
                )

                activity.findViewById<Button>(R.id.drive_disconnect).performClick()
                assertEquals(android.view.View.GONE, activity.findViewById<Button>(R.id.drive_export_now).visibility)
            }
        }
    }

    @Test
    fun last_export_summary_is_rendered_in_ui() {
        val gateway = FakeGateway()
        MainActivity.driveAuthorizationGatewayFactory = { _, _ -> gateway }

        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SharedPreferencesExportStateStore(context)
        store.saveExecutionSummary(
            ExportExecutionSummary(
                outcome = ExportOutcome.SUCCESS,
                batchId = "batch-ui-100",
                recordCount = 42,
                executionTimestamp = java.time.Instant.parse("2026-08-30T12:00:00Z"),
                message = "Exported batch batch-ui-100 (42 records) to Google Drive.",
            ),
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val statusView = activity.findViewById<TextView>(R.id.drive_export_status)
                val text = statusView.text.toString()
                assertTrue(text.contains("batch-ui-100"))
                assertTrue(text.contains("42"))
            }
        }

        store.clear()
    }
}

