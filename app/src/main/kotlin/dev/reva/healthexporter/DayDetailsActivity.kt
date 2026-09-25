package dev.reva.healthexporter

import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CancellationException

fun interface DayDetailsSource {
    suspend fun load(date: LocalDate, zone: ZoneId): DayDetails
}

class DayDetailsActivity : ComponentActivity() {
    companion object {
        const val DATES = "selected_dates"
        internal var loaderFactory: ((Context) -> DayDetailsSource)? = null
    }

    private lateinit var dates: List<LocalDate>
    private lateinit var zone: ZoneId
    private lateinit var dateSelector: Spinner
    private lateinit var typeSelector: Spinner
    private lateinit var recordsView: ListView
    private var loaded: DayDetails? = null
    private var selectedDate = 0
    private var selectedType = 0
    private var loadJob: Job? = null
    private var generation = 0
    private var restoredScroll: Parcelable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dates = intent.getStringArrayListExtra(DATES).orEmpty().mapNotNull {
            runCatching { LocalDate.parse(it) }.getOrNull()
        }.distinct().sorted()
        if (dates.isEmpty()) { finish(); return }
        zone = ZoneId.of(savedInstanceState?.getString("zone") ?: ZoneId.systemDefault().id)
        selectedDate = (savedInstanceState?.getInt("date") ?: 0).coerceIn(dates.indices)
        selectedType = (savedInstanceState?.getInt("type") ?: 0).coerceIn(DetailType.entries.indices)
        @Suppress("DEPRECATION")
        restoredScroll = savedInstanceState?.getParcelable("scroll")
        setContentView(R.layout.activity_day_details)
        // The app targets edge-to-edge Android versions as well as API 30.
        findViewById<View>(R.id.details_root).setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            val padding = (16 * resources.displayMetrics.density).toInt()
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
            insets
        }
        findViewById<View>(R.id.details_back).setOnClickListener { finish() }
        findViewById<View>(R.id.details_retry).setOnClickListener { restoredScroll = null; loadDay() }
        findViewById<TextView>(R.id.details_scope).text = getString(R.string.details_scope, zone.id)
        dateSelector = findViewById(R.id.details_dates)
        typeSelector = findViewById(R.id.details_types)
        recordsView = findViewById(R.id.details_records)
        // Restore explicitly after async loading, rather than letting ListView restore an empty adapter.
        recordsView.isSaveEnabled = false
        dateSelector.adapter = spinnerAdapter(dates.map(LocalDate::toString))
        dateSelector.setSelection(selectedDate)
        typeSelector.adapter = spinnerAdapter(DetailType.entries.map { it.label })
        typeSelector.isSaveEnabled = false
        dateSelector.isSaveEnabled = false
        typeSelector.setSelection(selectedType)
        dateSelector.onItemSelectedListener = selectionListener { position ->
            if (position != selectedDate) {
                selectedDate = position
                restoredScroll = null
                loadDay()
            }
        }
        typeSelector.onItemSelectedListener = selectionListener { position ->
            if (position != selectedType) {
                selectedType = position
                restoredScroll = null
                renderGroup()
            }
        }
        loadDay()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("date", selectedDate)
        outState.putInt("type", selectedType)
        outState.putString("zone", zone.id)
        outState.putParcelable("scroll", restoredScroll ?: recordsView.onSaveInstanceState())
        super.onSaveInstanceState(outState)
    }

    private fun loadDay() {
        loadJob?.cancel()
        val request = ++generation
        val date = dates[selectedDate]
        loaded = null
        updateTypes()
        renderGroup()
        loadJob = lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val source = loaderFactory?.invoke(this@DayDetailsActivity) ?: deviceSource()
                    source.load(date, zone)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DayDetails(date, DetailType.entries.map {
                    DetailGroup(it, "Read failed", problem = getString(R.string.details_failed))
                })
            }
            if (request != generation) return@launch
            loaded = result
            updateTypes()
            renderGroup()
        }
    }

    private suspend fun deviceSource(): DayDetailsSource {
        val reader = try {
            if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
                DayDetailsReader { _, _ -> throw UnsupportedOperationException() }
            } else {
                val client = HealthConnectClient.getOrCreate(this)
                HealthConnectDayDetailsReader(client, client.permissionController.getGrantedPermissions())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DayDetailsReader { _, _ -> throw IllegalStateException() }
        }
        val loader = DayDetailsLoader(reader, emaEventStore(this))
        return DayDetailsSource(loader::load)
    }

    private fun updateTypes() {
        @Suppress("UNCHECKED_CAST")
        val adapter = typeSelector.adapter as ArrayAdapter<String>
        adapter.setNotifyOnChange(false)
        adapter.clear()
        adapter.addAll(DetailType.entries.map { type ->
            val group = loaded?.groups?.firstOrNull { it.type == type }
            if (group == null) type.label else "${type.label} · ${if (group.problem != null) group.summary else group.records.size}"
        })
        adapter.notifyDataSetChanged()
    }

    private fun renderGroup() {
        val group = loaded?.groups?.firstOrNull { it.type == DetailType.entries[selectedType] }
        findViewById<TextView>(R.id.details_summary).text = group?.summary ?: getString(R.string.details_loading)
        findViewById<TextView>(R.id.details_status).apply {
            text = when {
                group == null -> ""
                group.problem != null -> group.problem
                group.records.isEmpty() -> getString(R.string.details_empty)
                else -> getString(R.string.details_raw_totals)
            }
            visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
        val rows = group?.records.orEmpty()
        recordsView.adapter = object : ArrayAdapter<DetailRecord>(this, android.R.layout.simple_list_item_1, rows) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = (convertView as? TextView) ?: TextView(context).apply {
                    textSize = 14f
                    setTextColor(android.graphics.Color.rgb(21, 29, 29))
                    val padding = (12 * resources.displayMetrics.density).toInt()
                    setPadding(padding, padding, padding, padding)
                    setBackgroundColor(android.graphics.Color.WHITE)
                }
                view.text = getItem(position)?.text
                return view
            }
        }
        if (group != null) {
            restoredScroll?.let { recordsView.onRestoreInstanceState(it) }
            restoredScroll = null
        }
    }

    private fun spinnerAdapter(labels: List<String>) = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }

    private fun selectionListener(select: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = select(position)
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }
}
