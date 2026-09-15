package dev.reva.healthexporter

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.LocalTime

interface EmaConfigStore {
    fun load(): EmaConfig
    fun save(config: EmaConfig)
}

class SharedPreferencesEmaConfigStore(
    private val preferences: SharedPreferences,
) : EmaConfigStore {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    override fun load(): EmaConfig {
        val serialized = preferences.getString(KEY_CONFIG, null) ?: return EmaConfig()
        return deserializeEmaConfig(serialized) ?: EmaConfig()
    }

    override fun save(config: EmaConfig) {
        preferences.edit().putString(KEY_CONFIG, serializeEmaConfig(config)).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "reva_ema_config"
        const val KEY_CONFIG = "config_v1"
    }
}

fun serializeEmaConfig(config: EmaConfig): String = Gson().toJson(JsonObject().apply {
    addProperty("activeStart", config.activeStart.toString())
    addProperty("activeEnd", config.activeEnd.toString())
    addProperty("checkInsPerDay", config.checkInsPerDay)
    addProperty("notificationsEnabled", config.notificationsEnabled)
    add("activityCategories", JsonArray().apply {
        config.activityCategories.forEach { category ->
            add(JsonObject().apply {
                addProperty("id", category.id)
                addProperty("label", category.label)
            })
        }
    })
})

fun deserializeEmaConfig(serialized: String): EmaConfig? = try {
    val json = JsonParser.parseString(serialized).asJsonObject
    val categories = json.getAsJsonArray("activityCategories")?.map { element ->
        val category = element.asJsonObject
        EmaActivityCategory(category.get("id").asString, category.get("label").asString)
    }.orEmpty()
    EmaConfig(
        activeStart = LocalTime.parse(json.get("activeStart").asString),
        activeEnd = LocalTime.parse(json.get("activeEnd").asString),
        checkInsPerDay = json.get("checkInsPerDay").asInt,
        notificationsEnabled = json.get("notificationsEnabled").asBoolean,
        activityCategories = categories,
    )
} catch (_: Exception) {
    null
}
