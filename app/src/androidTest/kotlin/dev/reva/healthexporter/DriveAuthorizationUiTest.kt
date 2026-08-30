package dev.reva.healthexporter

import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
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
        override fun disconnect() { disconnects += 1 }
    }

    @After
    fun tearDown() {
        MainActivity.resetDriveAuthorizationGatewayFactory()
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
}
