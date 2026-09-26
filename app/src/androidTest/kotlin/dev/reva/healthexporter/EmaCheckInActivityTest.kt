package dev.reva.healthexporter

import android.content.Intent
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
                (activities.getChildAt(1) as RadioButton).performClick()
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

    @Test
    fun controlsStartUnsetAndAnyMeaningfulAnswerControlsSubmit() {
        val (intent, store) = form("optional-controls")
        ActivityScenario.launch<EmaCheckInActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val submit = activity.findViewById<Button>(R.id.ema_submit)
                val sliders = listOf(R.id.ema_mood, R.id.ema_energy, R.id.ema_focus, R.id.ema_stress)
                    .map { activity.findViewById<SeekBar>(it) }
                assertTrue(sliders.all { it.progress == 0 && it.stateDescription == "Not set" })
                assertTrue(sliders.all { it.thumbTintList?.defaultColor == activity.getColor(R.color.ema_unset) })
                assertEquals("Not set", activity.findViewById<TextView>(R.id.ema_mood_value).text)
                assertFalse(submit.isEnabled)
                activity.findViewById<EditText>(R.id.ema_note).setText("Synthetic note only")
                assertFalse(submit.isEnabled)

                sliders.forEach { slider ->
                    slider.progress = 1
                    assertTrue(submit.isEnabled)
                    assertEquals("1 of 5", slider.stateDescription)
                    assertNotEquals(activity.getColor(R.color.ema_unset), slider.thumbTintList?.defaultColor)
                    slider.progress = 0
                    assertFalse(submit.isEnabled)
                }

                val activities = activity.findViewById<RadioGroup>(R.id.ema_activity)
                assertEquals(-1, activities.checkedRadioButtonId)
                (activities.getChildAt(0) as RadioButton).performClick()
                assertFalse(submit.isEnabled)
                (activities.getChildAt(1) as RadioButton).performClick()
                assertTrue(submit.isEnabled)
                (activities.getChildAt(0) as RadioButton).performClick()
                assertFalse(submit.isEnabled)
            }
        }
        assertEquals(EmaResponseStatus.PENDING, store.get("optional-controls")?.status)
    }

    @Test
    fun submitsOneSliderWithoutActivityAndActivityWithoutSliders() {
        val (sliderIntent, sliderStore) = form("slider-only")
        ActivityScenario.launch<EmaCheckInActivity>(sliderIntent).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<SeekBar>(R.id.ema_focus).progress = 5
                activity.findViewById<Button>(R.id.ema_submit).performClick()
            }
        }
        assertEquals(EmaAnswers(focus = 5), sliderStore.get("slider-only")?.answers)
        assertEquals(null, sliderStore.get("slider-only")?.activity)

        val (activityIntent, activityStore) = form("activity-only")
        ActivityScenario.launch<EmaCheckInActivity>(activityIntent).use { scenario ->
            scenario.onActivity { activity ->
                val activities = activity.findViewById<RadioGroup>(R.id.ema_activity)
                (activities.getChildAt(1) as RadioButton).performClick()
                activity.findViewById<Button>(R.id.ema_submit).performClick()
            }
        }
        assertEquals(null, activityStore.get("activity-only")?.answers)
        assertEquals("work_coding", activityStore.get("activity-only")?.activity)
    }

    @Test
    fun recreationPreservesAnswersAndSubmitState() {
        val (intent, _) = form("recreate")
        ActivityScenario.launch<EmaCheckInActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<SeekBar>(R.id.ema_energy).progress = 3
                val activities = activity.findViewById<RadioGroup>(R.id.ema_activity)
                (activities.getChildAt(1) as RadioButton).performClick()
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(3, activity.findViewById<SeekBar>(R.id.ema_energy).progress)
                assertEquals("work_coding", activity.findViewById<RadioGroup>(R.id.ema_activity)
                    .findViewById<RadioButton>(activity.findViewById<RadioGroup>(R.id.ema_activity).checkedRadioButtonId).tag)
                assertTrue(activity.findViewById<Button>(R.id.ema_submit).isEnabled)
            }
        }
    }

    private fun form(eventId: String): Pair<Intent, InMemoryEmaEventStore> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = InMemoryEmaEventStore().apply {
            save(EmaEvent.pending(eventId, Instant.parse("2026-09-15T09:00:00Z"), ZoneId.of("UTC")))
        }
        EmaCheckInActivity.eventStoreFactory = { store }
        EmaCheckInActivity.configStoreFactory = {
            object : EmaConfigStore {
                override fun load() = EmaConfig(activityCategories = listOf(EmaActivityCategory("work_coding", "Work / coding")))
                override fun save(config: EmaConfig) = Unit
            }
        }
        return Intent(context, EmaCheckInActivity::class.java)
            .putExtra(WorkManagerEmaGateway.KEY_EVENT_ID, eventId) to store
    }

    @Test
    fun failedResponseTransitionKeepsFormOpenWithoutFinishing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = InMemoryEmaEventStore().apply {
            save(
                EmaEvent.pending(
                    "expired-event",
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
        val intent = Intent(context, EmaCheckInActivity::class.java)
            .putExtra(WorkManagerEmaGateway.KEY_EVENT_ID, "expired-event")

        ActivityScenario.launch<EmaCheckInActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                store.save(store.get("expired-event")!!.copy(status = EmaResponseStatus.EXPIRED))
                val activities = activity.findViewById<RadioGroup>(R.id.ema_activity)
                (activities.getChildAt(1) as RadioButton).performClick()
                activity.findViewById<Button>(R.id.ema_submit).performClick()
                assertFalse(activity.isFinishing)
            }
        }
    }

    @Test
    fun backPressDismissesPendingCheckIn() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = InMemoryEmaEventStore().apply {
            save(
                EmaEvent.pending(
                    "back-event",
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
        val intent = Intent(context, EmaCheckInActivity::class.java)
            .putExtra(WorkManagerEmaGateway.KEY_EVENT_ID, "back-event")

        ActivityScenario.launch<EmaCheckInActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
                assertTrue(activity.isFinishing)
            }
        }

        val event = store.get("back-event")
        assertEquals(EmaResponseStatus.DISMISSED, event?.status)
    }
}
