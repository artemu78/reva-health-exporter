package dev.reva.healthexporter

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest
import java.time.Instant

interface ExportHistoryStore {
    fun entries(destinationKey: String): List<ExportHistoryEntry>
    fun upsert(entry: ExportHistoryEntry)
    fun replaceConfirmed(destinationKey: String, entries: List<ExportHistoryEntry>)
}

class InMemoryExportHistoryStore : ExportHistoryStore {
    private val values = mutableListOf<ExportHistoryEntry>()

    override fun entries(destinationKey: String) = values.filter { it.destinationKey == destinationKey }

    override fun upsert(entry: ExportHistoryEntry) {
        values.removeAll { it.destinationKey == entry.destinationKey && it.batchId == entry.batchId }
        values += entry
    }

    override fun replaceConfirmed(destinationKey: String, entries: List<ExportHistoryEntry>) {
        val confirmedIds = entries.map { it.batchId }.toSet()
        values.removeAll {
            it.destinationKey == destinationKey &&
                (it.status == HistoryBatchStatus.CONFIRMED || it.batchId in confirmedIds)
        }
        values += entries.filter { it.destinationKey == destinationKey && it.status == HistoryBatchStatus.CONFIRMED }
    }
}

class SharedPreferencesExportHistoryStore(
    private val preferences: SharedPreferences,
) : ExportHistoryStore {
    constructor(context: Context) : this(context.getSharedPreferences("reva_export_history", Context.MODE_PRIVATE))

    override fun entries(destinationKey: String): List<ExportHistoryEntry> =
        readAll().filter { it.destinationKey == destinationKey }

    override fun upsert(entry: ExportHistoryEntry) {
        val all = readAll().filterNot {
            it.destinationKey == entry.destinationKey && it.batchId == entry.batchId
        } + entry
        writeAll(all)
    }

    override fun replaceConfirmed(destinationKey: String, entries: List<ExportHistoryEntry>) {
        val confirmedIds = entries.map { it.batchId }.toSet()
        val pendingAndOther = readAll().filterNot {
            it.destinationKey == destinationKey &&
                (it.status == HistoryBatchStatus.CONFIRMED || it.batchId in confirmedIds)
        }
        writeAll(pendingAndOther + entries.filter { it.destinationKey == destinationKey })
    }

    private fun readAll(): List<ExportHistoryEntry> = try {
        val raw = preferences.getString("entries", null) ?: return emptyList()
        JsonParser.parseString(raw).asJsonArray.mapNotNull { element ->
            val obj = element.asJsonObject
            parseDriveHistoryEntry(
                mapOf(
                    "batchId" to obj.get("batchId")?.asString.orEmpty(),
                    "windowStart" to obj.get("windowStart")?.asString.orEmpty(),
                    "windowEnd" to obj.get("windowEnd")?.asString.orEmpty(),
                    "historyStatus" to obj.get("status")?.asString.orEmpty(),
                    "historyUpdatedAt" to obj.get("updatedAt")?.asString.orEmpty(),
                ),
                obj.get("destinationKey")?.asString.orEmpty(),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun writeAll(entries: List<ExportHistoryEntry>) {
        val json = JsonArray().apply {
            entries.forEach { entry ->
                add(JsonObject().apply {
                    addProperty("batchId", entry.batchId)
                    addProperty("windowStart", entry.coveredInterval.startInclusive.toString())
                    addProperty("windowEnd", entry.coveredInterval.endExclusive.toString())
                    addProperty("status", entry.status.name)
                    addProperty("destinationKey", entry.destinationKey)
                    addProperty("updatedAt", entry.updatedAt.toString())
                })
            }
        }
        preferences.edit().putString("entries", Gson().toJson(json)).commit()
    }
}

fun parseDriveHistoryEntry(properties: Map<String, String>, destinationKey: String): ExportHistoryEntry? {
    return try {
        val id = properties["batchId"]?.takeIf { it.isNotBlank() } ?: return null
        val start = Instant.parse(properties["windowStart"] ?: return null)
        val end = Instant.parse(properties["windowEnd"] ?: return null)
        ExportHistoryEntry(
            id,
            TimeWindow(start, end),
            HistoryBatchStatus.valueOf(properties["historyStatus"] ?: HistoryBatchStatus.CONFIRMED.name),
            destinationKey.takeIf { it.isNotBlank() } ?: return null,
            properties["historyUpdatedAt"]?.let(Instant::parse) ?: Instant.EPOCH,
        )
    } catch (_: Exception) {
        null
    }
}

fun destinationHistoryKey(accountId: String?, destinationName: String): String {
    val source = "${accountId ?: "anonymous"}|$destinationName"
    return MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
        .take(16).joinToString("") { "%02x".format(it) }
}

fun stableBackfillBatchId(destinationKey: String, window: TimeWindow): String {
    val source = "$destinationKey|${window.startInclusive}|${window.endExclusive}"
    return "backfill-" + MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
        .take(16).joinToString("") { "%02x".format(it) }
}
