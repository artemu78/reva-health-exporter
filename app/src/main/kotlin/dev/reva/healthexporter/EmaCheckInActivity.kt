package dev.reva.healthexporter

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.time.Instant

class EmaCheckInActivity : ComponentActivity() {
    companion object {
        internal var eventStoreFactory: (EmaCheckInActivity) -> EmaEventStore = { emaEventStore(it) }
        internal var configStoreFactory: (EmaCheckInActivity) -> EmaConfigStore = { SharedPreferencesEmaConfigStore(it) }
        internal var notificationFactory: (EmaCheckInActivity) -> EmaNotificationGateway = {
            AndroidEmaNotificationGateway(it)
        }
        internal var now: () -> Instant = Instant::now

        internal fun resetTestFactories() {
            eventStoreFactory = { emaEventStore(it) }
            configStoreFactory = { SharedPreferencesEmaConfigStore(it) }
            notificationFactory = { AndroidEmaNotificationGateway(it) }
            now = Instant::now
        }
    }

    private lateinit var eventId: String
    private lateinit var store: EmaEventStore
    private var selectedActivity: EmaActivityCategory? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        eventId = intent.getStringExtra(WorkManagerEmaGateway.KEY_EVENT_ID).orEmpty()
        store = eventStoreFactory(this)
        if (eventId.isBlank() || store.get(eventId)?.status != EmaResponseStatus.PENDING) {
            finish()
            return
        }
        notificationFactory(this).cancel(eventId)

        setContentView(R.layout.activity_ema_check_in)
        bindSlider(R.id.ema_mood, R.id.ema_mood_value)
        bindSlider(R.id.ema_energy, R.id.ema_energy_value)
        bindSlider(R.id.ema_focus, R.id.ema_focus_value)
        bindSlider(R.id.ema_stress, R.id.ema_stress_value)
        bindActivities(configStoreFactory(this).load().activityCategories)
        findViewById<Button>(R.id.ema_submit).setOnClickListener { submit() }
        findViewById<Button>(R.id.ema_skip).setOnClickListener {
            EmaPromptHandler(store, notificationFactory(this)).dismiss(eventId)
            finish()
        }
    }

    private fun bindSlider(sliderId: Int, valueId: Int) {
        val slider = findViewById<SeekBar>(sliderId)
        val value = findViewById<TextView>(valueId)
        value.text = slider.progress.toString()
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                value.text = progress.toString()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun bindActivities(categories: List<EmaActivityCategory>) {
        val group = findViewById<RadioGroup>(R.id.ema_activity)
        categories.forEach { category ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                tag = category.id
                text = category.label
                textSize = 16f
                minHeight = dp(48)
            })
        }
        group.setOnCheckedChangeListener { radioGroup, checkedId ->
            val selectedId = radioGroup.findViewById<RadioButton>(checkedId)?.tag as? String
            selectedActivity = categories.firstOrNull { it.id == selectedId }
            findViewById<Button>(R.id.ema_submit).isEnabled = selectedActivity != null
        }
    }

    private fun submit() {
        val activity = selectedActivity ?: return
        val saved = EmaCheckInService(store).answer(
            eventId = eventId,
            answeredAt = now(),
            answers = EmaAnswers(
                mood = findViewById<SeekBar>(R.id.ema_mood).progress,
                energy = findViewById<SeekBar>(R.id.ema_energy).progress,
                focus = findViewById<SeekBar>(R.id.ema_focus).progress,
                stress = findViewById<SeekBar>(R.id.ema_stress).progress,
            ),
            activity = activity.id,
            activityLabel = activity.label,
            note = findViewById<EditText>(R.id.ema_note).text.toString(),
        )
        if (saved) notificationFactory(this).cancel(eventId)
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
