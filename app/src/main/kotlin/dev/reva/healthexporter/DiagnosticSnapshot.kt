package dev.reva.healthexporter

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.Strictness
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.DateTimeException

data class DiagnosticSnapshot(
    val schemaVersion: Int,
    val appVersion: String,
    val androidVersion: String,
    val permissions: SnapshotPermissions,
    val window: SnapshotTimeCoverage,
    val types: List<SnapshotTypeSummary>,
)

data class SnapshotPermissions(
    val granted: List<String>,
    val missing: List<String>,
)

data class SnapshotTimeCoverage(
    val oldest: Instant,
    val newest: Instant,
)

data class SnapshotTypeSummary(
    val type: String,
    val status: String,
    val count: Int,
    val origins: List<String>,
    val timeCoverage: SnapshotTimeCoverage?,
)

class InvalidDiagnosticSnapshotException(cause: Throwable? = null) :
    IllegalArgumentException("Invalid diagnostic snapshot", cause)

class DiagnosticSnapshotSerializer {
    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .setStrictness(Strictness.STRICT)
        .create()

    fun serialize(snapshot: DiagnosticSnapshot): String = gson.toJson(snapshot.toJson())

    fun parse(json: String): DiagnosticSnapshot = try {
        val root = gson.fromJson(json, JsonObject::class.java)
            ?: throw InvalidDiagnosticSnapshotException()
        root.toSnapshot()
    } catch (error: InvalidDiagnosticSnapshotException) {
        throw error
    } catch (error: JsonParseException) {
        throw InvalidDiagnosticSnapshotException(error)
    } catch (error: IllegalStateException) {
        throw InvalidDiagnosticSnapshotException(error)
    } catch (error: NullPointerException) {
        throw InvalidDiagnosticSnapshotException(error)
    } catch (error: DateTimeException) {
        throw InvalidDiagnosticSnapshotException(error)
    }

    private fun DiagnosticSnapshot.toJson() = JsonObject().apply {
        addProperty("schemaVersion", schemaVersion)
        addProperty("appVersion", appVersion)
        addProperty("androidVersion", androidVersion)
        add("permissions", permissions.toJson())
        add("window", window.toJson())
        add(
            "types",
            JsonArray().also { array ->
                types.sortedBy(SnapshotTypeSummary::type).forEach { array.add(it.toJson()) }
            },
        )
    }

    private fun SnapshotPermissions.toJson() = JsonObject().apply {
        add("granted", granted.sorted().toJsonArray())
        add("missing", missing.sorted().toJsonArray())
    }

    private fun SnapshotTimeCoverage.toJson() = JsonObject().apply {
        addProperty("oldest", oldest.toString())
        addProperty("newest", newest.toString())
    }

    private fun SnapshotTypeSummary.toJson() = JsonObject().apply {
        addProperty("type", type)
        addProperty("status", status)
        addProperty("count", count)
        add("origins", origins.sorted().toJsonArray())
        timeCoverage?.let { add("timeCoverage", it.toJson()) }
    }

    private fun List<String>.toJsonArray() = JsonArray().also { array -> forEach(array::add) }

    private fun JsonObject.toSnapshot(): DiagnosticSnapshot {
        val version = requiredInt("schemaVersion")
        if (version != CURRENT_SCHEMA_VERSION) throw InvalidDiagnosticSnapshotException()
        val typesArray = getAsJsonArray("types") ?: throw InvalidDiagnosticSnapshotException()
        return DiagnosticSnapshot(
            schemaVersion = version,
            appVersion = requiredString("appVersion"),
            androidVersion = requiredString("androidVersion"),
            permissions = getAsJsonObject("permissions").toPermissions(),
            window = getAsJsonObject("window").toCoverage(),
            types = typesArray.map { it.asJsonObject.toTypeSummary() },
        )
    }

    private fun JsonObject.toPermissions() = SnapshotPermissions(
        granted = requiredStrings("granted"),
        missing = requiredStrings("missing"),
    )

    private fun JsonObject.toTypeSummary() = SnapshotTypeSummary(
        type = requiredString("type"),
        status = requiredString("status"),
        count = requiredInt("count").also { if (it < 0) throw InvalidDiagnosticSnapshotException() },
        origins = requiredStrings("origins"),
        timeCoverage = get("timeCoverage")?.takeUnless { it.isJsonNull }?.asJsonObject?.toCoverage(),
    )

    private fun JsonObject.toCoverage(): SnapshotTimeCoverage {
        val oldest = Instant.parse(requiredString("oldest"))
        val newest = Instant.parse(requiredString("newest"))
        if (oldest.isAfter(newest)) throw InvalidDiagnosticSnapshotException()
        return SnapshotTimeCoverage(oldest, newest)
    }

    private fun JsonObject.requiredString(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?.takeIf(String::isNotBlank) ?: throw InvalidDiagnosticSnapshotException()

    private fun JsonObject.requiredInt(name: String): Int =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
            ?: throw InvalidDiagnosticSnapshotException()

    private fun JsonObject.requiredStrings(name: String): List<String> =
        getAsJsonArray(name)?.map { element ->
            element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                ?: throw InvalidDiagnosticSnapshotException()
        } ?: throw InvalidDiagnosticSnapshotException()

    private companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

fun diagnosticSnapshot(
    result: DiagnosticProbeResult,
    appVersion: String,
    androidVersion: String,
    grantedMetrics: Set<HealthMetric>,
): DiagnosticSnapshot = DiagnosticSnapshot(
    schemaVersion = 1,
    appVersion = appVersion,
    androidVersion = androidVersion,
    permissions = SnapshotPermissions(
        granted = grantedMetrics.map(HealthMetric::displayName).sorted(),
        missing = (HealthConnectConfiguration.selectedMetrics - grantedMetrics)
            .map(HealthMetric::displayName)
            .sorted(),
    ),
    window = SnapshotTimeCoverage(result.window.startInclusive, result.window.endExclusive),
    types = HealthMetric.entries.map { metric ->
        val summary = result.summaries[metric] ?: MetricProbeSummary(metric, MetricProbeStatus.FAILED)
        SnapshotTypeSummary(
            type = metric.displayName,
            status = summary.status.name.lowercase(),
            count = summary.count,
            origins = summary.dataOrigins.sorted(),
            timeCoverage = if (summary.oldestTimestamp != null && summary.newestTimestamp != null) {
                SnapshotTimeCoverage(summary.oldestTimestamp, summary.newestTimestamp)
            } else {
                null
            },
        )
    },
)

fun interface DocumentOutput {
    fun open(destination: String): OutputStream?
}

sealed interface DocumentExportResult {
    data object Success : DocumentExportResult
    data object Cancelled : DocumentExportResult
    data object DestinationUnavailable : DocumentExportResult
    data object WriteFailed : DocumentExportResult
}

class DiagnosticDocumentExporter(private val output: DocumentOutput) {
    fun export(destination: String?, content: String): DocumentExportResult {
        if (destination == null) return DocumentExportResult.Cancelled
        return try {
            val stream = output.open(destination) ?: return DocumentExportResult.DestinationUnavailable
            stream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            DocumentExportResult.Success
        } catch (_: IOException) {
            DocumentExportResult.WriteFailed
        } catch (_: SecurityException) {
            DocumentExportResult.WriteFailed
        }
    }
}
