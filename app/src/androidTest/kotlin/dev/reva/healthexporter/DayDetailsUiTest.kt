package dev.reva.healthexporter

import android.content.Intent
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DayDetailsUiTest {
    @After fun reset() { DayDetailsActivity.loaderFactory = null }

    @Test fun datesAndGroupsStayVisibleWhileRecordsScrollAndSurviveRecreation() {
        DayDetailsActivity.loaderFactory = { _ -> DayDetailsSource { date, _ ->
            DayDetails(date, DetailType.entries.map { type ->
                DetailGroup(type, if (type == DetailType.STEPS) "300 records · 30000 steps (raw sum)" else "300 records", (1..300).map { DetailRecord("${type.label} record $it\n2026-09-20 10:00 → 10:15 UTC\n100 steps\nSource: synthetic.example\nExcluded by export source policy\nRecord ID: synthetic-$it", false) })
            })
        } }
        val intent = Intent(ApplicationProvider.getApplicationContext(), DayDetailsActivity::class.java)
            .putStringArrayListExtra(DayDetailsActivity.DATES, arrayListOf("2026-09-19", "2026-09-20"))
        ActivityScenario.launch<DayDetailsActivity>(intent).use { scenario ->
            awaitLoaded(scenario)
            scenario.onActivity {
                assertEquals(2, it.findViewById<Spinner>(R.id.details_dates).count)
                it.findViewById<Spinner>(R.id.details_dates).setSelection(1)
                it.findViewById<Spinner>(R.id.details_types).setSelection(2)
            }
            awaitLoaded(scenario)
            scenario.onActivity {
                val records = it.findViewById<ListView>(R.id.details_records)
                assertEquals(300, records.count)
                records.setSelection(250)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                assertTrue(it.findViewById<ListView>(R.id.details_records).firstVisiblePosition > 0)
                assertTrue(it.findViewById<Spinner>(R.id.details_dates).isShown)
                assertTrue(it.findViewById<Spinner>(R.id.details_types).isShown)
                assertEquals("300 records · 30000 steps (raw sum)", it.findViewById<TextView>(R.id.details_summary).text.toString())
            }
            if (InstrumentationRegistry.getArguments().getString("captureDetails") == "true") {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val capture = instrumentation.uiAutomation.executeShellCommand(
                    "screencap -p /data/local/tmp/reva-details-synthetic.png")
                android.os.ParcelFileDescriptor.AutoCloseInputStream(capture).use { it.readBytes() }
            }
            scenario.recreate()
            awaitLoaded(scenario)
            scenario.onActivity {
                assertEquals("2026-09-20", it.findViewById<Spinner>(R.id.details_dates).selectedItem.toString())
                assertEquals(2, it.findViewById<Spinner>(R.id.details_types).selectedItemPosition)
                assertTrue(it.findViewById<ListView>(R.id.details_records).firstVisiblePosition > 0)
                it.findViewById<android.view.View>(R.id.details_back).performClick()
                assertTrue(it.isFinishing)
            }
        }
    }

    @Test fun failureCanBeRetriedAndEmptyIsNotAnError() {
        var calls = 0
        DayDetailsActivity.loaderFactory = { _ -> DayDetailsSource { date, _ ->
            if (++calls == 1) throw java.io.IOException("synthetic failure")
            DayDetails(date, DetailType.entries.map { DetailGroup(it, "0 records") })
        } }
        val intent = Intent(ApplicationProvider.getApplicationContext(), DayDetailsActivity::class.java)
            .putStringArrayListExtra(DayDetailsActivity.DATES, arrayListOf("2026-09-20"))
        ActivityScenario.launch<DayDetailsActivity>(intent).use { scenario ->
            awaitSummary(scenario, "Read failed")
            scenario.onActivity { it.findViewById<android.view.View>(R.id.details_retry).performClick() }
            awaitSummary(scenario, "0 records")
            scenario.onActivity {
                assertTrue(it.findViewById<TextView>(R.id.details_status).text.toString().startsWith("No accessible records"))
            }
        }
    }

    @Test fun calendarDetailsWorksWithoutDriveAndBackPreservesSelection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(DayDetailsActivity::class.java.name, null, false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                val rows = it.findViewById<android.widget.LinearLayout>(R.id.export_history_rows)
                (rows.getChildAt(0) as android.view.ViewGroup).getChildAt(0).performClick()
                assertTrue(it.findViewById<android.view.View>(R.id.matrix_details).isEnabled)
                it.findViewById<android.view.View>(R.id.matrix_details).performClick()
            }
            val details = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
            assertNotNull("Details activity was not launched", details)
            instrumentation.runOnMainSync { details.onBackPressedDispatcherCompat() }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals("1 day selected", it.findViewById<TextView>(R.id.matrix_selection_count).text.toString())
            }
        }
        instrumentation.removeMonitor(monitor)
    }

    private fun android.app.Activity.onBackPressedDispatcherCompat() {
        (this as androidx.activity.ComponentActivity).onBackPressedDispatcher.onBackPressed()
    }

    private fun awaitSummary(scenario: ActivityScenario<DayDetailsActivity>, expected: String) {
        val deadline = System.nanoTime() + 10_000_000_000L
        do {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            var text = ""
            scenario.onActivity { text = it.findViewById<TextView>(R.id.details_summary).text.toString() }
            if (text == expected) return
            Thread.sleep(20)
        } while (System.nanoTime() < deadline)
        fail("Expected summary: $expected")
    }

    private fun awaitLoaded(scenario: ActivityScenario<DayDetailsActivity>) {
        val deadline = System.nanoTime() + 10_000_000_000L
        do {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            var loaded = false
            scenario.onActivity { loaded = it.findViewById<ListView>(R.id.details_records).count == 300 }
            if (loaded) return
            Thread.sleep(20)
        } while (System.nanoTime() < deadline)
        fail("Details did not load")
    }
}
