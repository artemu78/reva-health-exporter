package dev.reva.healthexporter

import android.view.View
import android.widget.Button
import android.view.ViewGroup
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
    fun matrixShowsFiveWeeksAndClearsSelectionWithoutEnablingDisconnectedUpload() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val rows = activity.findViewById<LinearLayout>(R.id.export_history_rows)
                assertEquals(5, rows.childCount)
                val firstWeek = rows.getChildAt(0) as ViewGroup
                assertEquals(7, firstWeek.childCount)
                val first = firstWeek.getChildAt(0)
                assertTrue(first.contentDescription.contains("Unknown"))
                first.performClick()
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.matrix_selection).visibility)
                assertFalse(activity.findViewById<Button>(R.id.export_history_upload_selected).isEnabled)
                activity.findViewById<View>(R.id.matrix_clear).performClick()
                assertEquals(View.GONE, activity.findViewById<View>(R.id.matrix_selection).visibility)
                assertFalse(activity.findViewById<View>(R.id.diagnostic_refresh).isShown)
                assertFalse(activity.findViewById<View>(R.id.drive_export_now).isShown)
                activity.findViewById<View>(R.id.matrix_nav_settings).performClick()
                assertTrue(activity.findViewById<View>(R.id.matrix_preferences).isShown)
                assertFalse(activity.findViewById<View>(R.id.matrix_records).isShown)
            }
        }
    }

    @Test
    fun navigationReturnsToTodayAndDisablesFutureDates() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val range = activity.findViewById<TextView>(R.id.matrix_range).text.toString()
                assertFalse(activity.findViewById<View>(R.id.matrix_next).isEnabled)
                activity.findViewById<View>(R.id.matrix_previous).performClick()
                assertTrue(activity.findViewById<View>(R.id.matrix_next).isEnabled)
                activity.findViewById<View>(R.id.matrix_today).performClick()
                assertEquals(range, activity.findViewById<TextView>(R.id.matrix_range).text.toString())
                val rows = activity.findViewById<LinearLayout>(R.id.export_history_rows)
                val today = java.time.LocalDate.now()
                uploadMatrixDates(today, 0).forEachIndexed { index, date ->
                    val cell = (rows.getChildAt(index / 7) as ViewGroup).getChildAt(index % 7)
                    assertEquals(date <= today, cell.isEnabled)
                }
            }
        }
    }

    @Test
    fun driveHistoryRefreshPreservesTheNavigatedWindow() {
        MainActivity.driveAuthorizationGatewayFactory = { _, complete ->
            object : DriveAuthorizationGateway {
                override fun launchAuthorization() { complete(DriveAuthorizationResult.Authorized("synthetic-account")) }
                override fun disconnect(onComplete: (DriveDisconnectionResult) -> Unit) { onComplete(DriveDisconnectionResult.Disconnected) }
            }
        }
        MainActivity.googleDriveGatewayFactory = { _, accountId -> EmptyGoogleDriveGateway(accountId) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.findViewById<Button>(R.id.drive_connect).performClick() }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            var range = ""
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.matrix_previous).performClick()
                range = activity.findViewById<TextView>(R.id.matrix_range).text.toString()
                activity.findViewById<Button>(R.id.export_history_refresh).performClick()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(range, activity.findViewById<TextView>(R.id.matrix_range).text.toString())
                assertEquals(5, activity.findViewById<LinearLayout>(R.id.export_history_rows).childCount)
            }
        }
    }

    @Test
    fun recreationKeepsWindowAndSelectedDates() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var range = ""
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.matrix_previous).performClick()
                range = activity.findViewById<TextView>(R.id.matrix_range).text.toString()
                val rows = activity.findViewById<LinearLayout>(R.id.export_history_rows)
                (rows.getChildAt(0) as ViewGroup).getChildAt(0).performClick()
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(range, activity.findViewById<TextView>(R.id.matrix_range).text.toString())
                assertEquals("1 days selected", activity.findViewById<TextView>(R.id.matrix_selection_count).text.toString())
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
