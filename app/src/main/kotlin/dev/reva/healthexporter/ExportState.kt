package dev.reva.healthexporter

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant
import java.util.UUID

data class ExportCheckpoint(
    val lastWindowEnd: Instant,
    val lastBatchId: String,
    val exportedAt: Instant,
    val totalRecordCount: Long,
) {
    init {
        require(lastBatchId.isNotBlank()) { "lastBatchId must not be blank" }
        require(totalRecordCount >= 0) { "totalRecordCount must be non-negative, got $totalRecordCount" }
    }
}

fun serializeExportCheckpoint(checkpoint: ExportCheckpoint): String {
    val json = JsonObject().apply {
        addProperty("lastWindowEnd", checkpoint.lastWindowEnd.toString())
        addProperty("lastBatchId", checkpoint.lastBatchId)
        addProperty("exportedAt", checkpoint.exportedAt.toString())
        addProperty("totalRecordCount", checkpoint.totalRecordCount)
    }
    return Gson().toJson(json)
}

fun deserializeExportCheckpoint(serialized: String): ExportCheckpoint? {
    if (serialized.isBlank()) return null
    return try {
        val json = JsonParser.parseString(serialized).asJsonObject
        val lastWindowEndStr = json.get("lastWindowEnd")?.asString ?: return null
        val lastBatchId = json.get("lastBatchId")?.asString?.takeIf(String::isNotBlank) ?: return null
        val exportedAtStr = json.get("exportedAt")?.asString ?: return null
        val totalRecordCount = json.get("totalRecordCount")?.asLong ?: return null
        if (totalRecordCount < 0) return null

        ExportCheckpoint(
            lastWindowEnd = Instant.parse(lastWindowEndStr),
            lastBatchId = lastBatchId,
            exportedAt = Instant.parse(exportedAtStr),
            totalRecordCount = totalRecordCount,
        )
    } catch (_: Exception) {
        null
    }
}

interface ExportStateStore {
    fun getInstallationId(): String
    fun getLastCheckpoint(): ExportCheckpoint?
    fun saveCheckpoint(checkpoint: ExportCheckpoint)
    fun getPendingBatch(): ExportBatch?
    fun savePendingBatch(batch: ExportBatch)
    fun clearPendingBatch()
    fun clear()
}

class InMemoryExportStateStore(
    installationId: String? = null,
) : ExportStateStore {
    private val persistentInstallationId: String = installationId ?: UUID.randomUUID().toString()
    private var checkpoint: ExportCheckpoint? = null
    private var pendingBatch: ExportBatch? = null

    var failOnSavePendingBatch: Throwable? = null
    var failOnSaveCheckpoint: Throwable? = null
    var failOnClearPendingBatch: Throwable? = null
    var failOnGetPendingBatch: Throwable? = null

    override fun getInstallationId(): String = persistentInstallationId

    override fun getLastCheckpoint(): ExportCheckpoint? = checkpoint

    override fun saveCheckpoint(checkpoint: ExportCheckpoint) {
        failOnSaveCheckpoint?.let { throw it }
        this.checkpoint = checkpoint
    }

    override fun getPendingBatch(): ExportBatch? {
        failOnGetPendingBatch?.let { throw it }
        return pendingBatch
    }

    override fun savePendingBatch(batch: ExportBatch) {
        failOnSavePendingBatch?.let { throw it }
        this.pendingBatch = batch
    }

    override fun clearPendingBatch() {
        failOnClearPendingBatch?.let { throw it }
        this.pendingBatch = null
    }

    override fun clear() {
        checkpoint = null
        pendingBatch = null
    }
}

class SharedPreferencesExportStateStore(
    private val preferences: SharedPreferences,
    private val serializer: ExportBatchSerializer = ExportBatchSerializer(),
) : ExportStateStore {

    constructor(context: Context, serializer: ExportBatchSerializer = ExportBatchSerializer()) : this(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        serializer,
    )

    override fun getInstallationId(): String {
        val existing = preferences.getString(KEY_INSTALLATION_ID, null)
        if (!existing.isNullOrBlank()) {
            return existing
        }
        val generated = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_INSTALLATION_ID, generated).commit()
        return generated
    }

    override fun getLastCheckpoint(): ExportCheckpoint? {
        val serialized = preferences.getString(KEY_LAST_CHECKPOINT_JSON, null) ?: return null
        val parsed = deserializeExportCheckpoint(serialized)
        if (parsed == null) {
            // Quarantine corrupt checkpoint entry safely
            preferences.edit()
                .remove(KEY_LAST_CHECKPOINT_JSON)
                .putString(KEY_CORRUPT_CHECKPOINT_BACKUP, serialized)
                .commit()
        }
        return parsed
    }

    override fun saveCheckpoint(checkpoint: ExportCheckpoint) {
        val serialized = serializeExportCheckpoint(checkpoint)
        preferences.edit()
            .putString(KEY_LAST_CHECKPOINT_JSON, serialized)
            .commit()
    }

    override fun getPendingBatch(): ExportBatch? {
        val ndjson = preferences.getString(KEY_PENDING_BATCH_NDJSON, null) ?: return null
        return try {
            serializer.parseNdjson(ndjson)
        } catch (_: Exception) {
            // Quarantine corrupt pending batch entry safely
            preferences.edit()
                .remove(KEY_PENDING_BATCH_NDJSON)
                .putString(KEY_CORRUPT_PENDING_BATCH_BACKUP, ndjson)
                .commit()
            null
        }
    }

    override fun savePendingBatch(batch: ExportBatch) {
        val ndjson = serializer.serializeToNdjson(batch)
        preferences.edit()
            .putString(KEY_PENDING_BATCH_NDJSON, ndjson)
            .commit()
    }

    override fun clearPendingBatch() {
        preferences.edit()
            .remove(KEY_PENDING_BATCH_NDJSON)
            .commit()
    }

    override fun clear() {
        preferences.edit()
            .remove(KEY_LAST_CHECKPOINT_JSON)
            .remove(KEY_PENDING_BATCH_NDJSON)
            .commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "reva_health_export_state"
        const val KEY_INSTALLATION_ID = "installation_id"
        const val KEY_LAST_CHECKPOINT_JSON = "last_export_checkpoint_json"
        const val KEY_PENDING_BATCH_NDJSON = "pending_export_batch_ndjson"
        const val KEY_CORRUPT_CHECKPOINT_BACKUP = "corrupt_last_checkpoint_backup"
        const val KEY_CORRUPT_PENDING_BATCH_BACKUP = "corrupt_pending_batch_backup"
    }
}
