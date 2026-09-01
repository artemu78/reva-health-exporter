package dev.reva.healthexporter

import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExportHistoryUiTest {
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
}
