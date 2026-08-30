package dev.reva.healthexporter

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant

interface BackgroundProbeStore {
    fun saveSummary(summary: BackgroundReadExecutionSummary)
    fun loadSummary(): BackgroundReadExecutionSummary?
    fun clear()
}

class InMemoryBackgroundProbeStore : BackgroundProbeStore {
    private var current: BackgroundReadExecutionSummary? = null

    override fun saveSummary(summary: BackgroundReadExecutionSummary) {
        current = summary
    }

    override fun loadSummary(): BackgroundReadExecutionSummary? = current

    override fun clear() {
        current = null
    }
}

class SharedPreferencesBackgroundProbeStore(
    private val preferences: SharedPreferences,
) : BackgroundProbeStore {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    override fun saveSummary(summary: BackgroundReadExecutionSummary) {
        val serialized = serializeBackgroundProbeSummary(summary)
        preferences.edit()
            .putString(KEY_SUMMARY_JSON, serialized)
            .apply()
    }

    override fun loadSummary(): BackgroundReadExecutionSummary? {
        val serialized = preferences.getString(KEY_SUMMARY_JSON, null) ?: return null
        return deserializeBackgroundProbeSummary(serialized)
    }

    override fun clear() {
        preferences.edit().remove(KEY_SUMMARY_JSON).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "reva_background_probe_store"
        const val KEY_SUMMARY_JSON = "last_background_probe_summary"
    }
}

fun serializeBackgroundProbeSummary(summary: BackgroundReadExecutionSummary): String {
    val json = JsonObject().apply {
        addProperty("outcome", summary.outcome.name)
        addProperty("message", summary.message)
        addProperty("totalRecords", summary.totalRecords)
        addProperty("readTypesCount", summary.readTypesCount)
        summary.executionTimestamp?.let {
            addProperty("executionTimestamp", it.toString())
        }
        val originsArray = com.google.gson.JsonArray()
        summary.dataOrigins.sorted().forEach { originsArray.add(it) }
        add("dataOrigins", originsArray)
    }
    return Gson().toJson(json)
}

fun deserializeBackgroundProbeSummary(serialized: String): BackgroundReadExecutionSummary? {
    if (serialized.isBlank()) return null
    return try {
        val json = JsonParser.parseString(serialized).asJsonObject
        val outcomeStr = json.get("outcome")?.asString ?: return null
        val outcome = try {
            BackgroundReadOutcome.valueOf(outcomeStr)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val message = json.get("message")?.asString ?: return null
        val totalRecords = json.get("totalRecords")?.asInt ?: 0
        val readTypesCount = json.get("readTypesCount")?.asInt ?: 0
        val timestamp = json.get("executionTimestamp")?.asString?.let { Instant.parse(it) }
        val dataOrigins = json.getAsJsonArray("dataOrigins")
            ?.mapNotNull { it.asString }
            ?.toSet()
            ?: emptySet()

        BackgroundReadExecutionSummary(
            outcome = outcome,
            message = message,
            totalRecords = totalRecords,
            readTypesCount = readTypesCount,
            executionTimestamp = timestamp,
            dataOrigins = dataOrigins,
        )
    } catch (_: Exception) {
        null
    }
}
