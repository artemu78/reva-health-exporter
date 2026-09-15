package dev.reva.healthexporter

import android.content.Intent
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmaCheckInActivityTest {
    @After
    fun resetFactories() {
        EmaCheckInActivity.resetTestFactories()
    }

    @Test
    fun fullScreenSlidersAndActivitySubmitNumericCheckIn() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = InMemoryEmaEventStore().apply {
            save(
                EmaEvent.pending(
                    "ui-event",
                    Instant.parse("2026-09-15T09:00:00Z"),
                    ZoneId.of("Europe/Moscow"),
                ),
            )
        }
        EmaCheckInActivity.eventStoreFactory = { store }
        EmaCheckInActivity.configStoreFactory = {
            object : EmaConfigStore {
                override fun load() = EmaConfig(
                    activityCategories = listOf(EmaActivityCategory("work_coding", "Work / coding")),
                )
                override fun save(config: EmaConfig) = Unit
            }
        }
        EmaCheckInActivity.now = { Instant.parse("2026-09-15T09:01:02.003Z") }
        val intent = Intent(context, EmaCheckInActivity::class.java)
            .putExtra(WorkManagerEmaGateway.KEY_EVENT_ID, "ui-event")

        ActivityScenario.launch<EmaCheckInActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<SeekBar>(R.id.ema_mood).isShown)
                assertEquals("Mood, 1 very bad to 5 very good", activity.findViewById<SeekBar>(R.id.ema_mood).contentDescription)
                activity.findViewById<SeekBar>(R.id.ema_mood).progress = 4
                activity.findViewById<SeekBar>(R.id.ema_energy).progress = 2
                activity.findViewById<SeekBar>(R.id.ema_focus).progress = 5
                activity.findViewById<SeekBar>(R.id.ema_stress).progress = 1
                val activities = activity.findViewById<RadioGroup>(R.id.ema_activity)
                (activities.getChildAt(0) as RadioButton).performClick()
                activity.findViewById<Button>(R.id.ema_submit).performClick()
            }
        }

        val event = store.get("ui-event")
        assertEquals(EmaResponseStatus.ANSWERED, event?.status)
        assertEquals(EmaAnswers(4, 2, 5, 1), event?.answers)
        assertEquals("work_coding", event?.activity)
        assertEquals("Work / coding", event?.activityLabel)
        assertEquals(Instant.parse("2026-09-15T09:01:02.003Z"), event?.answeredAt)
    }
}
