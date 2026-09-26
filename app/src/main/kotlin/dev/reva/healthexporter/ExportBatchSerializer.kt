package dev.reva.healthexporter

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.Strictness
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.time.DateTimeException
import java.time.Instant
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

open class ExportBatchSerializer {
    private val gson: Gson = GsonBuilder()
        .setStrictness(Strictness.STRICT)
        .create()

    open fun serializeToJson(batch: ExportBatch): String {
        val root = JsonObject().apply {
            addProperty("exportSchemaVersion", 1)
            addProperty("exportId", batch.header.batchId)
            addProperty("createdAt", batch.header.createdAt.toString())

            val healthConnectBatch = JsonObject().apply {
                addProperty("schemaVersion", batch.header.schemaVersion)
                add("header", ExportJsonCodec.encodeHeader(batch.header))
                val recordsArray = JsonArray()
                for (record in sortedRecords(batch.records)) {
                    recordsArray.add(ExportJsonCodec.encodeRecord(record))
                }
                add("records", recordsArray)
            }
            add("healthConnectBatch", healthConnectBatch)

            val emaEventsArray = JsonArray()
            val sortedEma = batch.emaEvents.sortedWith(
                compareBy({ it.scheduledAt }, { it.id }),
            )
            for (event in sortedEma) {
                emaEventsArray.add(emaEventToJson(event))
            }
            add("emaEvents", emaEventsArray)
        }
        return gson.toJson(root)
    }

    open fun parseJson(json: String): ExportBatch {
        if (json.isBlank()) {
            throw InvalidExportSchemaException("JSON input must not be empty or blank")
        }
        val root = try {
            gson.fromJson(json, JsonObject::class.java)
                ?: throw InvalidExportSchemaException("JSON root must be a valid JSON object")
        } catch (e: JsonParseException) {
            throw InvalidExportSchemaException("Failed to parse batch JSON", e)
        } catch (e: IllegalStateException) {
            throw InvalidExportSchemaException("Batch JSON root must be a JSON object", e)
        }

        if (root.has("exportSchemaVersion") || root.has("healthConnectBatch")) {
            return parseEnvelopeJson(root)
        }

        if (root.has("header") || (root.has("schemaVersion") && root.has("records"))) {
            return parseLegacyBatchJson(root)
        }

        throw InvalidExportSchemaException("Batch JSON missing 'header' object")
    }

    private fun parseEnvelopeJson(root: JsonObject): ExportBatch {
        val (exportId, createdAt) = parseEnvelopeMetadata(root)
        val (header, records) = parseHealthConnectBatch(root.get("healthConnectBatch"), exportId, createdAt)
        val emaEvents = parseEnvelopeEmaEvents(root)
        return ExportBatch(header = header, records = records, emaEvents = emaEvents)
    }

    private fun parseEnvelopeMetadata(root: JsonObject): Pair<String, Instant> {
        val exportSchemaVersion = root.requiredInt("exportSchemaVersion")
        if (exportSchemaVersion != 1) {
            throw InvalidExportSchemaException("Unsupported exportSchemaVersion: $exportSchemaVersion (expected 1)")
        }
        val exportId = root.get("exportId")?.asString?.takeIf(String::isNotBlank)
            ?: throw InvalidExportSchemaException("Missing or blank 'exportId'")
        val createdAtStr = root.get("createdAt")?.asString?.takeIf(String::isNotBlank)
            ?: throw InvalidExportSchemaException("Missing or blank 'createdAt'")
        val createdAt = try {
            Instant.parse(createdAtStr)
        } catch (e: DateTimeException) {
            throw InvalidExportSchemaException("Invalid createdAt timestamp: $createdAtStr", e)
        }
        return Pair(exportId, createdAt)
    }

    private fun parseHealthConnectBatch(
        hcBatchElem: JsonElement?,
        exportId: String,
        createdAt: Instant,
    ): Pair<BatchHeader, List<CanonicalRecord>> {
        if (hcBatchElem == null) {
            throw InvalidExportSchemaException("Missing 'healthConnectBatch'")
        }
        if (!hcBatchElem.isJsonObject) {
            throw InvalidExportSchemaException("'healthConnectBatch' must be a JSON object")
        }
        val hcBatchObj = hcBatchElem.asJsonObject
        val hcSchemaVersion = hcBatchObj.requiredInt("schemaVersion")
        if (hcSchemaVersion != 1) {
            throw InvalidExportSchemaException("Unsupported healthConnectBatch schemaVersion: $hcSchemaVersion (expected 1)")
        }
        val recordsArray = hcBatchObj.getAsJsonArray("records")
            ?: throw InvalidExportSchemaException("Missing 'records' array in healthConnectBatch")

        val records = parseRecordsArray(recordsArray)
        val header = resolveHealthConnectHeader(hcBatchObj, records, exportId, createdAt)
        return Pair(header, records)
    }

    private fun resolveHealthConnectHeader(
        hcBatchObj: JsonObject,
        records: List<CanonicalRecord>,
        exportId: String,
        createdAt: Instant,
    ): BatchHeader {
        val headerObj = hcBatchObj.get("header")
        if (headerObj != null && headerObj.isJsonObject) {
            return ExportJsonCodec.decodeHeader(headerObj.asJsonObject)
        }
        val start = records.minOfOrNull { it.startTime } ?: createdAt
        val maxEnd = records.maxOfOrNull { it.endTime }
        val end = when {
            maxEnd == null -> createdAt.plusSeconds(1)
            maxEnd == start -> start.plusSeconds(1)
            else -> maxEnd
        }
        val actualStart = if (start.isBefore(end)) start else end.minusSeconds(1)
        return BatchHeader(
            schemaVersion = 1,
            installationId = exportId,
            batchId = exportId,
            createdAt = createdAt,
            timeWindow = TimeWindow(actualStart, end),
            recordCount = records.size,
            recordTypes = records.map { it.recordType }.distinct().sorted(),
        )
    }

    private fun parseEnvelopeEmaEvents(root: JsonObject): List<EmaEvent> {
        val emaElem = root.get("emaEvents") ?: return emptyList()
        if (!emaElem.isJsonArray) {
            throw InvalidExportSchemaException("'emaEvents' must be a JSON array")
        }
        return emaElem.asJsonArray.mapIndexed { index, elem ->
            deserializeEmaEvent(elem.toString())
                ?: throw InvalidExportSchemaException("Failed to parse EMA event at index $index: $elem")
        }
    }

    private fun parseRecordsArray(recordsArray: JsonArray): List<CanonicalRecord> {
        return recordsArray.map { element ->
            if (!element.isJsonObject) {
                throw InvalidExportSchemaException("Record item in 'records' array must be a JSON object")
            }
            ExportJsonCodec.decodeRecord(element.asJsonObject)
        }
    }

    private fun parseLegacyBatchJson(root: JsonObject): ExportBatch {
        val headerObj = root.getAsJsonObject("header")
            ?: if (root.has("schemaVersion") && root.has("records")) root else throw InvalidExportSchemaException("Batch JSON missing 'header' object")
        val header = ExportJsonCodec.decodeHeader(headerObj)

        val recordsArray = root.getAsJsonArray("records")
            ?: throw InvalidExportSchemaException("Batch JSON missing 'records' array")

        val records = parseRecordsArray(recordsArray)
        return ExportBatch(header = header, records = records, emaEvents = emptyList())
    }

    open fun serializeToNdjson(batch: ExportBatch): String {
        val builder = StringBuilder()
        builder.append(gson.toJson(ExportJsonCodec.encodeHeader(batch.header))).append('\n')

        for (record in sortedRecords(batch.records)) {
            builder.append(gson.toJson(ExportJsonCodec.encodeRecord(record))).append('\n')
        }
        return builder.toString()
    }

    open fun parseNdjson(ndjson: String): ExportBatch {
        if (ndjson.isBlank()) {
            throw InvalidExportSchemaException("NDJSON input must not be empty or blank")
        }
        val lines = ndjson.lines().filter(String::isNotBlank)
        if (lines.isEmpty()) {
            throw InvalidExportSchemaException("NDJSON input must not be empty")
        }

        val headerObj = try {
            gson.fromJson(lines.first(), JsonObject::class.java)
                ?: throw InvalidExportSchemaException("First line must be a valid JSON object")
        } catch (e: JsonParseException) {
            throw InvalidExportSchemaException("Failed to parse batch header JSON", e)
        } catch (e: IllegalStateException) {
            throw InvalidExportSchemaException("Batch header must be a JSON object", e)
        }

        val header = ExportJsonCodec.decodeHeader(headerObj)
        val records = mutableListOf<CanonicalRecord>()

        for (line in lines.drop(1)) {
            val recordObj = try {
                gson.fromJson(line, JsonObject::class.java)
                    ?: throw InvalidExportSchemaException("Record line must be a valid JSON object")
            } catch (e: JsonParseException) {
                throw InvalidExportSchemaException("Failed to parse record JSON: $line", e)
            } catch (e: IllegalStateException) {
                throw InvalidExportSchemaException("Record line must be a JSON object", e)
            }
            records.add(ExportJsonCodec.decodeRecord(recordObj))
        }

        return ExportBatch(header = header, records = records)
    }

    open fun serializeToGzip(batch: ExportBatch, outputStream: OutputStream) {
        GZIPOutputStream(outputStream).bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(serializeToNdjson(batch))
        }
    }

    open fun serializeToGzipBytes(batch: ExportBatch): ByteArray {
        val byteStream = ByteArrayOutputStream()
        serializeToGzip(batch, byteStream)
        return byteStream.toByteArray()
    }

    open fun decompressAndParse(inputStream: InputStream): ExportBatch {
        val decompressedText = GZIPInputStream(inputStream).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return parseNdjson(decompressedText)
    }

    open fun decompressAndParse(gzipBytes: ByteArray): ExportBatch =
        ByteArrayInputStream(gzipBytes).use(::decompressAndParse)

    private fun sortedRecords(records: List<CanonicalRecord>): List<CanonicalRecord> = records.sortedWith(
        compareBy(
            { it.startTime },
            { it.recordType },
            { it.endTime },
            { it.metadata.recordId ?: it.metadata.clientRecordId ?: "" },
            { ExportJsonCodec.encodeRecord(it).toString() },
        ),
    )

}
