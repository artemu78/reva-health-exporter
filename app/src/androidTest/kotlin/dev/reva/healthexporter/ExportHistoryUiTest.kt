package dev.reva.healthexporter

import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExportHistoryUiTest {
    @After
    fun tearDown() {
        MainActivity.resetDriveAuthorizationGatewayFactory()
        MainActivity.resetGoogleDriveGatewayFactory()
    }

    @Test
    fun disconnectedHistoryShowsBoundedUnknownDaysAndTimezone() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val rows = activity.findViewById<LinearLayout>(R.id.export_history_rows)
                assertEquals(14, rows.childCount)
                val first = rows.getChildAt(0) as CheckBox
                assertTrue(first.text.toString().contains("Unknown"))
                assertTrue(first.isEnabled)
                assertEquals(
                    activity.getString(R.string.export_history_load_more),
                    activity.findViewById<Button>(R.id.export_history_load_more).text.toString(),
                )
                activity.findViewById<Button>(R.id.export_history_load_more).performClick()
                assertEquals(24, rows.childCount)
                assertFalse(activity.findViewById<Button>(R.id.export_history_upload_selected).isEnabled)
                assertTrue(activity.findViewById<TextView>(R.id.export_history_timezone).text.toString().startsWith("Calendar timezone:"))
                assertEquals(View.VISIBLE, activity.findViewById<TextView>(R.id.export_history_title).visibility)
            }
        }
    }

    @Test
    fun driveHistoryRefreshPreservesTheExpandedVisibleDateCount() {
        MainActivity.driveAuthorizationGatewayFactory = { _, complete ->
            object : DriveAuthorizationGateway {
                override fun launchAuthorization() {
                    complete(DriveAuthorizationResult.Authorized("synthetic-account"))
                }

                override fun disconnect(onComplete: (DriveDisconnectionResult) -> Unit) {
                    onComplete(DriveDisconnectionResult.Disconnected)
                }
            }
        }
        MainActivity.googleDriveGatewayFactory = { _, accountId -> EmptyGoogleDriveGateway(accountId) }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<Button>(R.id.drive_connect).performClick()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            scenario.onActivity { activity ->
                val rows = activity.findViewById<LinearLayout>(R.id.export_history_rows)
                activity.findViewById<Button>(R.id.export_history_load_more).performClick()
                assertEquals(24, rows.childCount)

                activity.findViewById<Button>(R.id.export_history_refresh).performClick()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            scenario.onActivity { activity ->
                val rows = activity.findViewById<LinearLayout>(R.id.export_history_rows)
                assertEquals(24, rows.childCount)
            }
        }
    }

    private class EmptyGoogleDriveGateway(
        override val accountId: String?,
    ) : GoogleDriveGateway {
        override suspend fun verifyAccess() = Unit

        override suspend fun findFolders(name: String, parentId: String?): List<GoogleDriveFile> = emptyList()

        override suspend fun createFolder(name: String, parentId: String?): GoogleDriveFile =
            error("Not used by history refresh")

        override suspend fun findFiles(
            parentFolderId: String?,
            name: String?,
            appProperties: Map<String, String>,
        ): List<GoogleDriveFile> = emptyList()

        override suspend fun uploadFile(
            name: String,
            mimeType: String,
            parentFolderId: String,
            appProperties: Map<String, String>,
            content: ByteArray,
        ): GoogleDriveFile = error("Not used by history refresh")

        override suspend fun updateFile(
            fileId: String,
            name: String,
            mimeType: String,
            appProperties: Map<String, String>,
            content: ByteArray,
        ): GoogleDriveFile = error("Not used by history refresh")

        override suspend fun downloadFile(fileId: String): ByteArray = error("Not used by history refresh")
    }
}
