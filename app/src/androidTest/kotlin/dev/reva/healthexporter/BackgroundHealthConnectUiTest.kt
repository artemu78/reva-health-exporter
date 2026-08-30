package dev.reva.healthexporter

import android.content.Context
import android.widget.Button
import android.widget.TextView
import androidx.health.connect.client.testing.FakeHealthConnectClient
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundHealthConnectUiTest {
    private val clock = object : DiagnosticClock {
        override fun now(zoneId: ZoneId): ZonedDateTime =
            ZonedDateTime.parse("2026-08-30T12:00:00Z[UTC]")
    }

    private val inMemoryStore = InMemoryBackgroundProbeStore()

    @Before
    fun setUp() {
        BackgroundProbeWorker.clock = clock
        BackgroundProbeWorker.storeFactory = { inMemoryStore }
    }

    @After
    fun tearDown() {
        BackgroundProbeWorker.resetDefaults()
    }

    @Test
    fun background_views_are_present_and_accessible() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val title = activity.findViewById<TextView>(R.id.background_read_title)
                val status = activity.findViewById<TextView>(R.id.background_read_status)
                val trigger = activity.findViewById<Button>(R.id.background_probe_trigger)
                val result = activity.findViewById<TextView>(R.id.background_probe_result)

                assertNotNull(title)
                assertNotNull(status)
                assertNotNull(trigger)
                assertNotNull(result)
                assertEquals("Test background read", trigger.text.toString())
                assertEquals(
                    "Trigger background Health Connect read worker",
                    trigger.contentDescription.toString(),
                )
            }
        }
    }

    @Test
    fun worker_executes_with_workmanager_test_builder() = runBlocking {
        val client = FakeHealthConnectClient()
        BackgroundProbeWorker.clientFactory = { client }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val worker = TestListenableWorkerBuilder<BackgroundProbeWorker>(context).build()

        val result = worker.doWork()
        assertNotNull(result)
        assertTrue(result is ListenableWorker.Result.Success)
    }

    @Test
    fun worker_handles_unsupported_provider_gracefully() = runBlocking {
        BackgroundProbeWorker.clientFactory = null

        val context = ApplicationProvider.getApplicationContext<Context>()
        val worker = TestListenableWorkerBuilder<BackgroundProbeWorker>(context).build()

        val result = worker.doWork()
        assertNotNull(result)
        assertTrue(result is ListenableWorker.Result.Success || result is ListenableWorker.Result.Failure)
    }

    @Test
    fun stored_probe_summary_is_restored_in_ui() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SharedPreferencesBackgroundProbeStore(context)
        store.saveSummary(
            BackgroundReadExecutionSummary(
                outcome = BackgroundReadOutcome.SUCCESS,
                message = "Successfully read 5 confirmed types.",
                totalRecords = 10,
                readTypesCount = 5,
                executionTimestamp = Instant.parse("2026-08-30T12:00:00Z"),
                dataOrigins = setOf("com.mi.health"),
            ),
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val result = activity.findViewById<TextView>(R.id.background_probe_result)
                val text = result.text.toString()
                assertTrue(text.contains("Success"))
                assertTrue(text.contains("10"))
                assertTrue(text.contains("com.mi.health"))
            }
        }

        store.clear()
    }
}
