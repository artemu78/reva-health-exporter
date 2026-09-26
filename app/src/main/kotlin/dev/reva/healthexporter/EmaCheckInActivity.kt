package dev.reva.healthexporter

import android.os.Bundle
import android.content.res.ColorStateList
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import java.time.Instant

class EmaCheckInActivity : ComponentActivity() {
    companion object {
        internal var eventStoreFactory: (EmaCheckInActivity) -> EmaEventStore = { emaEventStore(it) }
        internal var configStoreFactory: (EmaCheckInActivity) -> EmaConfigStore = { SharedPreferencesEmaConfigStore(it) }
        internal var notificationFactory: (EmaCheckInActivity) -> EmaNotificationGateway = {
            AndroidEmaNotificationGateway(it)
        }
        internal var now: () -> Instant = Instant::now
        private const val NOT_SET_ACTIVITY = "__not_set__"
        private const val STATE_ACTIVITY = "ema.activity"
        private const val STATE_SLIDER = "ema.slider."

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
    private lateinit var activityCategories: List<EmaActivityCategory>

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
        activityCategories = configStoreFactory(this).load().activityCategories
        bindActivities(activityCategories, savedInstanceState?.getString(STATE_ACTIVITY))
        if (savedInstanceState != null) {
            sliderIds.forEach { id -> findViewById<SeekBar>(id).progress = savedInstanceState.getInt("$STATE_SLIDER$id") }
        }
        updateSubmitEnabled()
        findViewById<Button>(R.id.ema_submit).setOnClickListener { submit() }
        findViewById<Button>(R.id.ema_skip).setOnClickListener {
            EmaPromptHandler(store, notificationFactory(this)).dismiss(eventId)
            finish()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                EmaPromptHandler(store, notificationFactory(this@EmaCheckInActivity)).dismiss(eventId)
                finish()
            }
        })
    }

    private fun bindSlider(sliderId: Int, valueId: Int) {
        val slider = findViewById<SeekBar>(sliderId)
        val value = findViewById<TextView>(valueId)
        val activeProgressTint = slider.progressTintList
        val activeThumbTint = slider.thumbTintList
        fun render(progress: Int) {
            val isSet = progress in 1..5
            value.text = if (isSet) progress.toString() else getString(R.string.ema_not_set)
            slider.stateDescription = if (isSet) getString(R.string.ema_value_of_five, progress) else getString(R.string.ema_not_set)
            slider.progressTintList = if (isSet) activeProgressTint else ColorStateList.valueOf(getColor(R.color.ema_unset))
            slider.thumbTintList = if (isSet) activeThumbTint else ColorStateList.valueOf(getColor(R.color.ema_unset))
        }
        render(slider.progress)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                render(progress)
                updateSubmitEnabled()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun bindActivities(categories: List<EmaActivityCategory>, restoredActivityId: String?) {
        val group = findViewById<RadioGroup>(R.id.ema_activity)
        group.addView(activityButton(NOT_SET_ACTIVITY, getString(R.string.ema_not_set)))
        categories.forEach { category ->
            group.addView(activityButton(category.id, category.label))
        }
        group.setOnCheckedChangeListener { radioGroup, checkedId ->
            val selectedId = radioGroup.findViewById<RadioButton>(checkedId)?.tag as? String
            selectedActivity = categories.firstOrNull { it.id == selectedId }
            updateSubmitEnabled()
        }
        restoredActivityId?.let { id ->
            (0 until group.childCount).map { group.getChildAt(it) as RadioButton }
                .firstOrNull { it.tag == id }?.isChecked = true
        }
    }

    private fun activityButton(activityId: String, label: String) = RadioButton(this).apply {
        id = View.generateViewId()
        tag = activityId
        text = label
        textSize = 16f
        minHeight = dp(48)
    }

    private fun updateSubmitEnabled() {
        if (!::activityCategories.isInitialized) return
        findViewById<Button>(R.id.ema_submit).isEnabled =
            sliderIds.any { findViewById<SeekBar>(it).progress in 1..5 } || selectedActivity != null
    }

    private fun submit() {
        val answers = EmaAnswers(
            mood = findViewById<SeekBar>(R.id.ema_mood).progress.answerOrNull(),
            energy = findViewById<SeekBar>(R.id.ema_energy).progress.answerOrNull(),
            focus = findViewById<SeekBar>(R.id.ema_focus).progress.answerOrNull(),
            stress = findViewById<SeekBar>(R.id.ema_stress).progress.answerOrNull(),
        ).takeIf { it.hasCoreAnswer() }
        val activity = selectedActivity
        if (answers == null && activity == null) return
        val saved = EmaCheckInService(store).answer(
            eventId = eventId,
            answeredAt = now(),
            answers = answers,
            activity = activity?.id,
            activityLabel = activity?.label,
            note = findViewById<EditText>(R.id.ema_note).text.toString(),
        )
        if (saved) {
            notificationFactory(this).cancel(eventId)
            finish()
        } else {
            Toast.makeText(this, R.string.ema_check_in_expired, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        sliderIds.forEach { id -> outState.putInt("$STATE_SLIDER$id", findViewById<SeekBar>(id).progress) }
        val group = findViewById<RadioGroup>(R.id.ema_activity)
        outState.putString(STATE_ACTIVITY, group.findViewById<RadioButton>(group.checkedRadioButtonId)?.tag as? String)
    }

    private fun Int.answerOrNull(): Int? = takeIf { it in 1..5 }
    private val sliderIds get() = listOf(R.id.ema_mood, R.id.ema_energy, R.id.ema_focus, R.id.ema_stress)

}
