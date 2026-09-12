package dev.reva.healthexporter

import android.content.Context
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
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

    private lateinit var workManager: WorkManager
    private lateinit var stateStore: SharedPreferencesExportStateStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Keep scheduled exports pending until a test explicitly satisfies their constraints.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
        stateStore = SharedPreferencesExportStateStore(context)
        stateStore.clear()
    }

    @After
    fun tearDown() {
        workManager.cancelAllWork().result.get()
        stateStore.clear()
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
                    activity.getString(R.string.drive_disconnected),
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
    fun successful_disconnect_restores_the_single_connect_action_and_clears_drive_messages() {
        val gateway = FakeGateway()
        MainActivity.driveAuthorizationGatewayFactory = { _, complete ->
            gateway.onLaunch = { complete(DriveAuthorizationResult.Authorized("synthetic-account")) }
            gateway
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<Button>(R.id.drive_connect).performClick()
                activity.findViewById<Button>(R.id.drive_disconnect).performClick()

                assertEquals(
                    android.view.View.VISIBLE,
                    activity.findViewById<Button>(R.id.drive_connect).visibility,
                )
                assertEquals(
                    android.view.View.GONE,
                    activity.findViewById<Button>(R.id.drive_reconnect).visibility,
                )
                assertEquals(
                    android.view.View.GONE,
                    activity.findViewById<Button>(R.id.drive_disconnect).visibility,
                )
                assertEquals(
                    android.view.View.GONE,
                    activity.findViewById<Button>(R.id.drive_export_now).visibility,
                )
                assertEquals(
                    activity.getString(R.string.drive_disconnected),
                    activity.findViewById<TextView>(R.id.drive_authorization_status).text.toString(),
                )
                assertEquals(
                    "",
                    activity.findViewById<TextView>(R.id.drive_export_status).text.toString(),
                )
            }
        }
    }

    @Test
    fun disconnect_failure_still_restores_the_local_connect_action() {
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
                    activity.getString(R.string.drive_disconnected),
                    activity.findViewById<TextView>(R.id.drive_authorization_status).text.toString(),
                )
                assertEquals(
                    android.view.View.VISIBLE,
                    activity.findViewById<Button>(R.id.drive_connect).visibility,
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
        MainActivity.driveAuthorizationGatewayFactory = { _, complete ->
            gateway.onLaunch = { complete(DriveAuthorizationResult.Authorized("synthetic-account")) }
            gateway
        }

        stateStore.saveExecutionSummary(
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
                activity.findViewById<Button>(R.id.drive_connect).performClick()
                val statusView = activity.findViewById<TextView>(R.id.drive_export_status)
                val text = statusView.text.toString()
                assertTrue(text.contains("batch-ui-100"))
                assertTrue(text.contains("42"))
            }
        }
    }

    @Test
    fun saved_export_failure_is_shown_when_drive_connects() {
        stateStore.saveExecutionSummary(
            ExportExecutionSummary(
                outcome = ExportOutcome.TERMINAL_FAILURE,
                executionTimestamp = java.time.Instant.parse("2026-08-30T12:00:00Z"),
                message = "Health Connect is unavailable on this device: Service not available",
            ),
        )
        val gateway = FakeGateway()
        MainActivity.driveAuthorizationGatewayFactory = { _, complete ->
            gateway.onLaunch = { complete(DriveAuthorizationResult.Authorized("synthetic-account")) }
            gateway
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<Button>(R.id.drive_connect).performClick()
                assertEquals(
                    "Export failed: Health Connect is unavailable on this device: Service not available",
                    activity.findViewById<TextView>(R.id.drive_export_status).text.toString(),
                )
                assertEquals(android.view.View.VISIBLE, activity.findViewById<Button>(R.id.drive_export_now).visibility)
            }
        }
    }
}
