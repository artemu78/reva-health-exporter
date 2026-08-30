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
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class ExportBatchSerializer {
    private val gson: Gson = GsonBuilder()
        .setStrictness(Strictness.STRICT)
        .create()

    fun serializeToNdjson(batch: ExportBatch): String {
        val builder = StringBuilder()
        builder.append(gson.toJson(batch.header.toJson())).append('\n')

        val sortedRecords = batch.records.sortedWith(
            compareBy(
                { it.startTime },
                { it.recordType },
                { it.metadata.recordId },
            ),
        )
        for (record in sortedRecords) {
            builder.append(gson.toJson(record.toJson())).append('\n')
        }
        return builder.toString()
    }

    fun parseNdjson(ndjson: String): ExportBatch {
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

        val header = headerObj.toBatchHeader()
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
            records.add(recordObj.toCanonicalRecord())
        }

        return ExportBatch(header = header, records = records)
    }

    fun serializeToGzip(batch: ExportBatch, outputStream: OutputStream) {
        GZIPOutputStream(outputStream).bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(serializeToNdjson(batch))
        }
    }

    fun serializeToGzipBytes(batch: ExportBatch): ByteArray {
        val byteStream = ByteArrayOutputStream()
        serializeToGzip(batch, byteStream)
        return byteStream.toByteArray()
    }

    fun decompressAndParse(inputStream: InputStream): ExportBatch {
        val decompressedText = GZIPInputStream(inputStream).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return parseNdjson(decompressedText)
    }

    fun decompressAndParse(gzipBytes: ByteArray): ExportBatch =
        ByteArrayInputStream(gzipBytes).use(::decompressAndParse)

    private fun BatchHeader.toJson(): JsonObject = JsonObject().apply {
        addProperty("recordType", "header")
        addProperty("schemaVersion", schemaVersion)
        addProperty("installationId", installationId)
        addProperty("batchId", batchId)
        addProperty("createdAt", createdAt.toString())
        add("timeWindow", JsonObject().apply {
            addProperty("startInclusive", timeWindow.startInclusive.toString())
            addProperty("endExclusive", timeWindow.endExclusive.toString())
        })
        addProperty("recordCount", recordCount)
        add("recordTypes", JsonArray().also { array ->
            recordTypes.sorted().forEach(array::add)
        })
    }

    private fun CanonicalRecord.toJson(): JsonObject = JsonObject().apply {
        addProperty("recordType", recordType)
        addProperty("recordId", metadata.recordId)
        addProperty("origin", metadata.origin)
        addProperty("startTime", startTime.toString())
        startZoneOffset?.let { addProperty("startZoneOffset", it.toString()) }
        addProperty("endTime", endTime.toString())
        endZoneOffset?.let { addProperty("endZoneOffset", it.toString()) }
        metadata.clientRecordId?.let { addProperty("clientRecordId", it) }
        metadata.clientRecordVersion?.let { addProperty("clientRecordVersion", it) }
        metadata.recordingMethod?.let { addProperty("recordingMethod", it) }
        metadata.device?.let { dev ->
            add("device", JsonObject().apply {
                dev.manufacturer?.let { addProperty("manufacturer", it) }
                dev.model?.let { addProperty("model", it) }
                dev.type?.let { addProperty("type", it) }
            })
        }
        metadata.lastModifiedTime?.let { addProperty("lastModifiedTime", it.toString()) }

        when (this@toJson) {
            is CanonicalStepsRecord -> {
                addProperty("count", count)
            }
            is CanonicalHeartRateRecord -> {
                add("samples", JsonArray().also { array ->
                    samples.forEach { sample ->
                        array.add(JsonObject().apply {
                            addProperty("time", sample.time.toString())
                            addProperty("beatsPerMinute", sample.beatsPerMinute)
                        })
                    }
                })
            }
            is CanonicalDistanceRecord -> {
                addProperty("distanceMeters", distanceMeters)
            }
            is CanonicalTotalCaloriesBurnedRecord -> {
                addProperty("energyKilocalories", energyKilocalories)
            }
            is CanonicalSleepSessionRecord -> {
                title?.let { addProperty("title", it) }
                notes?.let { addProperty("notes", it) }
                add("stages", JsonArray().also { array ->
                    stages.forEach { stage ->
                        array.add(JsonObject().apply {
                            addProperty("startTime", stage.startTime.toString())
                            addProperty("endTime", stage.endTime.toString())
                            addProperty("stage", stage.stage)
                        })
                    }
                })
            }
            is CanonicalExerciseSessionRecord -> {
                addProperty("exerciseType", exerciseType)
                title?.let { addProperty("title", it) }
                notes?.let { addProperty("notes", it) }
                add("segments", JsonArray().also { array ->
                    segments.forEach { segment ->
                        array.add(JsonObject().apply {
                            addProperty("startTime", segment.startTime.toString())
                            addProperty("endTime", segment.endTime.toString())
                            addProperty("segmentType", segment.segmentType)
                            addProperty("repetitions", segment.repetitions)
                        })
                    }
                })
                add("laps", JsonArray().also { array ->
                    laps.forEach { lap ->
                        array.add(JsonObject().apply {
                            addProperty("startTime", lap.startTime.toString())
                            addProperty("endTime", lap.endTime.toString())
                            lap.lengthMeters?.let { addProperty("lengthMeters", it) }
                        })
                    }
                })
            }
            is CanonicalRestingHeartRateRecord -> {
                addProperty("beatsPerMinute", beatsPerMinute)
            }
            is CanonicalOxygenSaturationRecord -> {
                addProperty("percentage", percentage)
            }
        }
    }

    private fun JsonObject.toBatchHeader(): BatchHeader {
        val type = requiredString("recordType")
        if (type != "header") {
            throw InvalidExportSchemaException("Expected recordType 'header', got '$type'")
        }
        val schemaVersion = requiredInt("schemaVersion")
        val installationId = requiredString("installationId")
        val batchId = requiredString("batchId")
        val createdAt = requiredInstant("createdAt")
        val windowObj = getAsJsonObject("timeWindow")
            ?: throw InvalidExportSchemaException("Batch header missing 'timeWindow'")
        val timeWindow = TimeWindow(
            startInclusive = windowObj.requiredInstant("startInclusive"),
            endExclusive = windowObj.requiredInstant("endExclusive"),
        )
        val recordCount = requiredInt("recordCount")
        val recordTypes = requiredStrings("recordTypes")

        return BatchHeader(
            schemaVersion = schemaVersion,
            installationId = installationId,
            batchId = batchId,
            createdAt = createdAt,
            timeWindow = timeWindow,
            recordCount = recordCount,
            recordTypes = recordTypes,
        )
    }

    private fun JsonObject.toCanonicalRecord(): CanonicalRecord {
        val recordType = requiredString("recordType")
        val recordId = requiredString("recordId")
        val origin = requiredString("origin")
        val startTime = requiredInstant("startTime")
        val startZoneOffset = optionalZoneOffset("startZoneOffset")
        val endTime = requiredInstant("endTime")
        val endZoneOffset = optionalZoneOffset("endZoneOffset")
        val clientRecordId = optionalString("clientRecordId")
        val clientRecordVersion = optionalLong("clientRecordVersion")
        val recordingMethod = optionalInt("recordingMethod")
        val device = get("device")?.takeUnless(JsonElement::isJsonNull)?.asJsonObject?.let { dev ->
            DeviceMetadata(
                manufacturer = dev.optionalString("manufacturer"),
                model = dev.optionalString("model"),
                type = dev.optionalInt("type"),
            )
        }
        val lastModifiedTime = optionalInstant("lastModifiedTime")

        val metadata = RecordMetadata(
            recordId = recordId,
            origin = origin,
            clientRecordId = clientRecordId,
            clientRecordVersion = clientRecordVersion,
            recordingMethod = recordingMethod,
            device = device,
            lastModifiedTime = lastModifiedTime,
        )

        return when (recordType) {
            CanonicalStepsRecord.TYPE -> CanonicalStepsRecord(
                startTime = startTime,
                startZoneOffset = startZoneOffset,
                endTime = endTime,
                endZoneOffset = endZoneOffset,
                metadata = metadata,
                count = requiredLong("count"),
            )
            CanonicalHeartRateRecord.TYPE -> CanonicalHeartRateRecord(
                startTime = startTime,
                startZoneOffset = startZoneOffset,
                endTime = endTime,
                endZoneOffset = endZoneOffset,
                metadata = metadata,
                samples = (getAsJsonArray("samples") ?: throw InvalidExportSchemaException("Missing 'samples' array"))
                    .map { sampleEl ->
                        val sampleObj = sampleEl.asJsonObject
                        HeartRateSample(
                            time = sampleObj.requiredInstant("time"),
                            beatsPerMinute = sampleObj.requiredLong("beatsPerMinute"),
                        )
                    },
            )
            CanonicalDistanceRecord.TYPE -> CanonicalDistanceRecord(
                startTime = startTime,
                startZoneOffset = startZoneOffset,
                endTime = endTime,
                endZoneOffset = endZoneOffset,
                metadata = metadata,
                distanceMeters = requiredDouble("distanceMeters"),
            )
            CanonicalTotalCaloriesBurnedRecord.TYPE -> CanonicalTotalCaloriesBurnedRecord(
                startTime = startTime,
                startZoneOffset = startZoneOffset,
                endTime = endTime,
                endZoneOffset = endZoneOffset,
                metadata = metadata,
                energyKilocalories = requiredDouble("energyKilocalories"),
            )
            CanonicalSleepSessionRecord.TYPE -> CanonicalSleepSessionRecord(
                startTime = startTime,
                startZoneOffset = startZoneOffset,
                endTime = endTime,
                endZoneOffset = endZoneOffset,
                metadata = metadata,
                title = optionalString("title"),
                notes = optionalString("notes"),
                stages = (getAsJsonArray("stages") ?: JsonArray()).map { stageEl ->
                    val stageObj = stageEl.asJsonObject
                    SleepStage(
                        startTime = stageObj.requiredInstant("startTime"),
                        endTime = stageObj.requiredInstant("endTime"),
                        stage = stageObj.requiredInt("stage"),
                    )
                },
            )
            CanonicalExerciseSessionRecord.TYPE -> CanonicalExerciseSessionRecord(
                startTime = startTime,
                startZoneOffset = startZoneOffset,
                endTime = endTime,
                endZoneOffset = endZoneOffset,
                metadata = metadata,
                exerciseType = requiredInt("exerciseType"),
                title = optionalString("title"),
                notes = optionalString("notes"),
                segments = (getAsJsonArray("segments") ?: JsonArray()).map { segEl ->
                    val segObj = segEl.asJsonObject
                    ExerciseSegmentModel(
                        startTime = segObj.requiredInstant("startTime"),
                        endTime = segObj.requiredInstant("endTime"),
                        segmentType = segObj.requiredInt("segmentType"),
                        repetitions = segObj.optionalInt("repetitions") ?: 0,
                    )
                },
                laps = (getAsJsonArray("laps") ?: JsonArray()).map { lapEl ->
                    val lapObj = lapEl.asJsonObject
                    ExerciseLapModel(
                        startTime = lapObj.requiredInstant("startTime"),
                        endTime = lapObj.requiredInstant("endTime"),
                        lengthMeters = lapObj.optionalDouble("lengthMeters"),
                    )
                },
            )
            CanonicalRestingHeartRateRecord.TYPE -> CanonicalRestingHeartRateRecord(
                startTime = startTime,
                startZoneOffset = startZoneOffset,
                endTime = endTime,
                endZoneOffset = endZoneOffset,
                metadata = metadata,
                beatsPerMinute = requiredLong("beatsPerMinute"),
            )
            CanonicalOxygenSaturationRecord.TYPE -> CanonicalOxygenSaturationRecord(
                startTime = startTime,
                startZoneOffset = startZoneOffset,
                endTime = endTime,
                endZoneOffset = endZoneOffset,
                metadata = metadata,
                percentage = requiredDouble("percentage"),
            )
            else -> throw InvalidExportSchemaException("Unsupported recordType: '$recordType'")
        }
    }

    private fun JsonObject.requiredString(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?.takeIf(String::isNotBlank)
            ?: throw InvalidExportSchemaException("Field '$name' must be a non-blank string")

    private fun JsonObject.optionalString(name: String): String? =
        get(name)?.takeUnless(JsonElement::isJsonNull)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?.takeIf(String::isNotBlank)

    private fun JsonObject.requiredInt(name: String): Int =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.let {
            try {
                it.asInt
            } catch (e: Exception) {
                throw InvalidExportSchemaException("Field '$name' must be an integer", e)
            }
        } ?: throw InvalidExportSchemaException("Field '$name' must be an integer")

    private fun JsonObject.optionalInt(name: String): Int? =
        get(name)?.takeUnless(JsonElement::isJsonNull)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

    private fun JsonObject.requiredLong(name: String): Long =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.let {
            try {
                it.asLong
            } catch (e: Exception) {
                throw InvalidExportSchemaException("Field '$name' must be a number", e)
            }
        } ?: throw InvalidExportSchemaException("Field '$name' must be a number")

    private fun JsonObject.optionalLong(name: String): Long? =
        get(name)?.takeUnless(JsonElement::isJsonNull)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

    private fun JsonObject.requiredDouble(name: String): Double =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.let {
            try {
                it.asDouble
            } catch (e: Exception) {
                throw InvalidExportSchemaException("Field '$name' must be a number", e)
            }
        } ?: throw InvalidExportSchemaException("Field '$name' must be a number")

    private fun JsonObject.optionalDouble(name: String): Double? =
        get(name)?.takeUnless(JsonElement::isJsonNull)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

    private fun JsonObject.requiredInstant(name: String): Instant {
        val stringVal = requiredString(name)
        return try {
            Instant.parse(stringVal)
        } catch (e: DateTimeException) {
            throw InvalidExportSchemaException("Field '$name' has invalid ISO-8601 instant format: '$stringVal'", e)
        }
    }

    private fun JsonObject.optionalInstant(name: String): Instant? {
        val stringVal = optionalString(name) ?: return null
        return try {
            Instant.parse(stringVal)
        } catch (e: DateTimeException) {
            throw InvalidExportSchemaException("Field '$name' has invalid ISO-8601 instant format: '$stringVal'", e)
        }
    }

    private fun JsonObject.optionalZoneOffset(name: String): ZoneOffset? {
        val stringVal = optionalString(name) ?: return null
        return try {
            ZoneOffset.of(stringVal)
        } catch (e: DateTimeException) {
            throw InvalidExportSchemaException("Field '$name' has invalid zone offset format: '$stringVal'", e)
        }
    }

    private fun JsonObject.requiredStrings(name: String): List<String> =
        getAsJsonArray(name)?.map { element ->
            element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                ?: throw InvalidExportSchemaException("Array '$name' must contain string elements")
        } ?: throw InvalidExportSchemaException("Field '$name' must be an array of strings")
}
