package dev.reva.healthexporter

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

internal class EmaSettingsController(
    private val activity: ComponentActivity,
    private val configStore: EmaConfigStore = SharedPreferencesEmaConfigStore(activity),
    private val eventStore: EmaEventStore = emaEventStore(activity),
) {
    private val scheduleCoordinator by lazy {
        EmaScheduleCoordinator(
            store = eventStore,
            workGateway = WorkManagerEmaGateway(activity),
            promptHandler = EmaPromptHandler(eventStore, AndroidEmaNotificationGateway(activity)),
        )
    }

    fun setup() {
        val config = configStore.load()
        val enabled = view<Switch>(R.id.ema_enabled)
        val start = view<Button>(R.id.ema_active_start)
        val end = view<Button>(R.id.ema_active_end)
        val count = view<SeekBar>(R.id.ema_count)
        val countValue = view<TextView>(R.id.ema_count_value)
        val categories = view<EditText>(R.id.ema_categories)

        enabled.isChecked = config.notificationsEnabled
        start.tag = config.activeStart
        end.tag = config.activeEnd
        renderTimeButton(start, R.string.ema_active_start)
        renderTimeButton(end, R.string.ema_active_end)
        count.progress = config.checkInsPerDay
        countValue.text = activity.getString(R.string.ema_count, count.progress)
        count.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                countValue.text = activity.getString(R.string.ema_count, progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        categories.setText(config.activityCategories.joinToString("\n") { it.label })
        start.setOnClickListener { chooseTime(start, R.string.ema_active_start) }
        end.setOnClickListener { chooseTime(end, R.string.ema_active_end) }
        view<Button>(R.id.ema_save_settings).setOnClickListener { save() }
        view<Button>(R.id.ema_check_in_now).setOnClickListener { startManualCheckIn() }
        renderSummary()
    }

    fun reconcileSchedule() {
        val config = configStore.load()
        scheduleCoordinator.reconcile(Instant.now(), ZoneId.systemDefault(), config)
        requestNotificationPermission(config)
    }

    fun renderSummary() {
        val events = eventStore.all()
        view<TextView>(R.id.ema_settings_status).text = activity.getString(
            R.string.ema_summary,
            events.count { it.status == EmaResponseStatus.ANSWERED },
            events.count { it.status == EmaResponseStatus.DISMISSED },
            events.count { it.status == EmaResponseStatus.EXPIRED },
        )
    }

    private fun chooseTime(button: Button, labelResource: Int) {
        val current = button.tag as LocalTime
        TimePickerDialog(
            activity,
            { _, hour, minute ->
                button.tag = LocalTime.of(hour, minute)
                renderTimeButton(button, labelResource)
            },
            current.hour,
            current.minute,
            true,
        ).show()
    }

    private fun renderTimeButton(button: Button, labelResource: Int) {
        val value = (button.tag as LocalTime).format(TIME_FORMAT)
        button.text = activity.getString(labelResource, value)
    }

    private fun save() {
        val oldConfig = configStore.load()
        val labels = view<EditText>(R.id.ema_categories).text.lines()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        val start = view<Button>(R.id.ema_active_start).tag as LocalTime
        val end = view<Button>(R.id.ema_active_end).tag as LocalTime
        if (labels.isEmpty() || start == end) {
            view<TextView>(R.id.ema_settings_status).text = activity.getString(R.string.ema_settings_invalid)
            return
        }

        val config = EmaConfig(
            activeStart = start,
            activeEnd = end,
            checkInsPerDay = view<SeekBar>(R.id.ema_count).progress,
            notificationsEnabled = view<Switch>(R.id.ema_enabled).isChecked,
            activityCategories = categoriesFor(labels, oldConfig),
        )
        configStore.save(config)
        scheduleCoordinator.reconfigure(Instant.now(), ZoneId.systemDefault(), config)
        requestNotificationPermission(config)
        view<TextView>(R.id.ema_settings_status).text = activity.getString(R.string.ema_settings_saved)
    }

    private fun categoriesFor(labels: List<String>, oldConfig: EmaConfig): List<EmaActivityCategory> {
        val priorIds = oldConfig.activityCategories.associate { it.label to it.id }
        val usedIds = mutableSetOf<String>()
        return labels.mapIndexed { index, label ->
            val base = priorIds[label] ?: categoryIdBase(label, index)
            var id = base
            var suffix = 2
            while (!usedIds.add(id)) id = "${base}_${suffix++}"
            EmaActivityCategory(id, label)
        }
    }

    private fun categoryIdBase(label: String, index: Int): String = label.lowercase()
        .replace(NON_ID_CHARACTERS, "_")
        .trim('_')
        .ifBlank { "activity_${index + 1}" }

    private fun requestNotificationPermission(config: EmaConfig) {
        if (
            !config.notificationsEnabled || Build.VERSION.SDK_INT < 33 ||
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val preferences = activity.getSharedPreferences(EMA_PERMISSIONS, Context.MODE_PRIVATE)
        val hasRequested = preferences.getBoolean(REQUESTED_NOTIFICATIONS, false)
        if (!hasRequested || activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            preferences.edit().putBoolean(REQUESTED_NOTIFICATIONS, true).apply()
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
        }
    }

    private fun startManualCheckIn() {
        val eventId = UUID.randomUUID().toString()
        eventStore.save(EmaEvent.pending(eventId, Instant.now(), ZoneId.systemDefault()))
        activity.startActivity(
            Intent(activity, EmaCheckInActivity::class.java)
                .putExtra(WorkManagerEmaGateway.KEY_EVENT_ID, eventId),
        )
    }

    private inline fun <reified T> view(id: Int): T = activity.findViewById(id)

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val NON_ID_CHARACTERS = Regex("[^a-z0-9]+")
        const val EMA_PERMISSIONS = "reva_ema_permissions"
        const val REQUESTED_NOTIFICATIONS = "requested_post_notifications"
        const val NOTIFICATION_PERMISSION_REQUEST = 5601
    }
}
