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

open class ExportBatchSerializer {
    private val gson: Gson = GsonBuilder()
        .setStrictness(Strictness.STRICT)
        .create()

    open fun serializeToJson(batch: ExportBatch): String {
        val root = JsonObject().apply {
            add("header", batch.header.toJson())
            val sortedRecords = batch.records.sortedWith(
                compareBy(
                    { it.startTime },
                    { it.recordType },
                    { it.endTime },
                    { it.metadata.recordId ?: "" },
                ),
            )
            val recordsArray = JsonArray()
            for (record in sortedRecords) {
                recordsArray.add(record.toJson())
            }
            add("records", recordsArray)
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

        val headerObj = root.getAsJsonObject("header")
            ?: if (root.has("schemaVersion") && root.has("records")) root else throw InvalidExportSchemaException("Batch JSON missing 'header' object")
        val header = headerObj.toBatchHeader()

        val recordsArray = root.getAsJsonArray("records")
            ?: throw InvalidExportSchemaException("Batch JSON missing 'records' array")

        val records = recordsArray.map { element ->
            if (!element.isJsonObject) {
                throw InvalidExportSchemaException("Record item in 'records' array must be a JSON object")
            }
            element.asJsonObject.toCanonicalRecord()
        }

        return ExportBatch(header = header, records = records)
    }

    open fun serializeToNdjson(batch: ExportBatch): String {
        val builder = StringBuilder()
        builder.append(gson.toJson(batch.header.toJson())).append('\n')

        val sortedRecords = batch.records.sortedWith(
            compareBy(
                { it.startTime },
                { it.recordType },
                { it.endTime },
                { it.metadata.recordId ?: "" },
            ),
        )
        for (record in sortedRecords) {
            builder.append(gson.toJson(record.toJson())).append('\n')
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
        addProperty("origin", metadata.origin)
        addProperty("startTime", startTime.toString())
        startZoneOffset?.let { addProperty("startZoneOffset", it.toString()) }
        addProperty("endTime", endTime.toString())
        endZoneOffset?.let { addProperty("endZoneOffset", it.toString()) }

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
        val type = optionalString("recordType")
        if (type != null && type != "header") {
            throw InvalidExportSchemaException("Expected recordType 'header', got '$type'")
        }
        val schemaVersion = requiredInt("schemaVersion")
        val installationId = requiredString("installationId")
        val batchId = requiredString("batchId")
        val createdAt = requiredInstant("createdAt")
        val windowEl = get("timeWindow")
            ?: throw InvalidExportSchemaException("Batch header missing 'timeWindow'")
        if (!windowEl.isJsonObject) {
            throw InvalidExportSchemaException("Field 'timeWindow' must be a JSON object")
        }
        val windowObj = windowEl.asJsonObject
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
        val origin = requiredString("origin")
        val startTime = requiredInstant("startTime")
        val startZoneOffset = optionalZoneOffset("startZoneOffset")
        val endTime = requiredInstant("endTime")
        val endZoneOffset = optionalZoneOffset("endZoneOffset")
        val recordId = optionalString("recordId")
        val clientRecordId = optionalString("clientRecordId")
        val clientRecordVersion = optionalLong("clientRecordVersion")
        val recordingMethod = optionalInt("recordingMethod")
        val deviceEl = get("device")
        val device = when {
            deviceEl == null || deviceEl.isJsonNull -> null
            deviceEl.isJsonObject -> {
                val dev = deviceEl.asJsonObject
                DeviceMetadata(
                    manufacturer = dev.optionalString("manufacturer"),
                    model = dev.optionalString("model"),
                    type = dev.optionalInt("type"),
                )
            }
            else -> throw InvalidExportSchemaException("Field 'device' must be a JSON object")
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
            CanonicalHeartRateRecord.TYPE -> {
                val samplesEl = get("samples") ?: throw InvalidExportSchemaException("Missing 'samples' array")
                if (!samplesEl.isJsonArray) {
                    throw InvalidExportSchemaException("Field 'samples' must be an array")
                }
                CanonicalHeartRateRecord(
                    startTime = startTime,
                    startZoneOffset = startZoneOffset,
                    endTime = endTime,
                    endZoneOffset = endZoneOffset,
                    metadata = metadata,
                    samples = samplesEl.asJsonArray.map { sampleEl ->
                        if (!sampleEl.isJsonObject) {
                            throw InvalidExportSchemaException("Heart rate sample must be a JSON object")
                        }
                        val sampleObj = sampleEl.asJsonObject
                        HeartRateSample(
                            time = sampleObj.requiredInstant("time"),
                            beatsPerMinute = sampleObj.requiredLong("beatsPerMinute"),
                        )
                    },
                )
            }
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
            CanonicalSleepSessionRecord.TYPE -> {
                val stagesEl = get("stages")
                val stagesList = when {
                    stagesEl == null || stagesEl.isJsonNull -> emptyList()
                    stagesEl.isJsonArray -> stagesEl.asJsonArray.map { stageEl ->
                        if (!stageEl.isJsonObject) {
                            throw InvalidExportSchemaException("Sleep stage must be a JSON object")
                        }
                        val stageObj = stageEl.asJsonObject
                        SleepStage(
                            startTime = stageObj.requiredInstant("startTime"),
                            endTime = stageObj.requiredInstant("endTime"),
                            stage = stageObj.requiredInt("stage"),
                        )
                    }
                    else -> throw InvalidExportSchemaException("Field 'stages' must be an array")
                }
                CanonicalSleepSessionRecord(
                    startTime = startTime,
                    startZoneOffset = startZoneOffset,
                    endTime = endTime,
                    endZoneOffset = endZoneOffset,
                    metadata = metadata,
                    title = optionalString("title"),
                    notes = optionalString("notes"),
                    stages = stagesList,
                )
            }
            CanonicalExerciseSessionRecord.TYPE -> {
                val segmentsEl = get("segments")
                val segmentsList = when {
                    segmentsEl == null || segmentsEl.isJsonNull -> emptyList()
                    segmentsEl.isJsonArray -> segmentsEl.asJsonArray.map { segEl ->
                        if (!segEl.isJsonObject) {
                            throw InvalidExportSchemaException("Exercise segment must be a JSON object")
                        }
                        val segObj = segEl.asJsonObject
                        ExerciseSegmentModel(
                            startTime = segObj.requiredInstant("startTime"),
                            endTime = segObj.requiredInstant("endTime"),
                            segmentType = segObj.requiredInt("segmentType"),
                            repetitions = segObj.optionalInt("repetitions") ?: 0,
                        )
                    }
                    else -> throw InvalidExportSchemaException("Field 'segments' must be an array")
                }
                val lapsEl = get("laps")
                val lapsList = when {
                    lapsEl == null || lapsEl.isJsonNull -> emptyList()
                    lapsEl.isJsonArray -> lapsEl.asJsonArray.map { lapEl ->
                        if (!lapEl.isJsonObject) {
                            throw InvalidExportSchemaException("Exercise lap must be a JSON object")
                        }
                        val lapObj = lapEl.asJsonObject
                        ExerciseLapModel(
                            startTime = lapObj.requiredInstant("startTime"),
                            endTime = lapObj.requiredInstant("endTime"),
                            lengthMeters = lapObj.optionalDouble("lengthMeters"),
                        )
                    }
                    else -> throw InvalidExportSchemaException("Field 'laps' must be an array")
                }
                CanonicalExerciseSessionRecord(
                    startTime = startTime,
                    startZoneOffset = startZoneOffset,
                    endTime = endTime,
                    endZoneOffset = endZoneOffset,
                    metadata = metadata,
                    exerciseType = requiredInt("exerciseType"),
                    title = optionalString("title"),
                    notes = optionalString("notes"),
                    segments = segmentsList,
                    laps = lapsList,
                )
            }
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

    private fun JsonObject.requiredString(name: String): String {
        val element = get(name) ?: throw InvalidExportSchemaException("Field '$name' is required")
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
            throw InvalidExportSchemaException("Field '$name' must be a string")
        }
        return element.asString.takeIf(String::isNotBlank)
            ?: throw InvalidExportSchemaException("Field '$name' must be a non-blank string")
    }

    private fun JsonObject.optionalString(name: String): String? {
        val element = get(name) ?: return null
        if (element.isJsonNull) return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
            throw InvalidExportSchemaException("Field '$name' must be a string")
        }
        return element.asString.takeIf(String::isNotBlank)
    }

    private fun JsonObject.requiredInt(name: String): Int {
        val element = get(name) ?: throw InvalidExportSchemaException("Field '$name' is required")
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
            throw InvalidExportSchemaException("Field '$name' must be an integer number")
        }
        val bigDecimal = try {
            element.asJsonPrimitive.asBigDecimal
        } catch (e: Exception) {
            throw InvalidExportSchemaException("Field '$name' must be an integer", e)
        }
        return try {
            bigDecimal.intValueExact()
        } catch (e: ArithmeticException) {
            throw InvalidExportSchemaException("Field '$name' must be an exact integer without fraction or overflow: $bigDecimal", e)
        }
    }

    private fun JsonObject.optionalInt(name: String): Int? {
        val element = get(name) ?: return null
        if (element.isJsonNull) return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
            throw InvalidExportSchemaException("Field '$name' must be an integer number")
        }
        val bigDecimal = try {
            element.asJsonPrimitive.asBigDecimal
        } catch (e: Exception) {
            throw InvalidExportSchemaException("Field '$name' must be an integer", e)
        }
        return try {
            bigDecimal.intValueExact()
        } catch (e: ArithmeticException) {
            throw InvalidExportSchemaException("Field '$name' must be an exact integer without fraction or overflow: $bigDecimal", e)
        }
    }

    private fun JsonObject.requiredLong(name: String): Long {
        val element = get(name) ?: throw InvalidExportSchemaException("Field '$name' is required")
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
            throw InvalidExportSchemaException("Field '$name' must be an integer number")
        }
        val bigDecimal = try {
            element.asJsonPrimitive.asBigDecimal
        } catch (e: Exception) {
            throw InvalidExportSchemaException("Field '$name' must be a number", e)
        }
        return try {
            bigDecimal.longValueExact()
        } catch (e: ArithmeticException) {
            throw InvalidExportSchemaException("Field '$name' must be an exact integer without fraction or overflow: $bigDecimal", e)
        }
    }

    private fun JsonObject.optionalLong(name: String): Long? {
        val element = get(name) ?: return null
        if (element.isJsonNull) return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
            throw InvalidExportSchemaException("Field '$name' must be an integer number")
        }
        val bigDecimal = try {
            element.asJsonPrimitive.asBigDecimal
        } catch (e: Exception) {
            throw InvalidExportSchemaException("Field '$name' must be a number", e)
        }
        return try {
            bigDecimal.longValueExact()
        } catch (e: ArithmeticException) {
            throw InvalidExportSchemaException("Field '$name' must be an exact integer without fraction or overflow: $bigDecimal", e)
        }
    }

    private fun JsonObject.requiredDouble(name: String): Double {
        val element = get(name) ?: throw InvalidExportSchemaException("Field '$name' is required")
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
            throw InvalidExportSchemaException("Field '$name' must be a number")
        }
        val value = try {
            element.asJsonPrimitive.asDouble
        } catch (e: Exception) {
            throw InvalidExportSchemaException("Field '$name' must be a number", e)
        }
        if (value.isNaN() || value.isInfinite()) {
            throw InvalidExportSchemaException("Field '$name' must be finite, got $value")
        }
        return value
    }

    private fun JsonObject.optionalDouble(name: String): Double? {
        val element = get(name) ?: return null
        if (element.isJsonNull) return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
            throw InvalidExportSchemaException("Field '$name' must be a number")
        }
        val value = try {
            element.asJsonPrimitive.asDouble
        } catch (e: Exception) {
            throw InvalidExportSchemaException("Field '$name' must be a number", e)
        }
        if (value.isNaN() || value.isInfinite()) {
            throw InvalidExportSchemaException("Field '$name' must be finite, got $value")
        }
        return value
    }

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

    private fun JsonObject.requiredStrings(name: String): List<String> {
        val element = get(name) ?: throw InvalidExportSchemaException("Field '$name' is required")
        if (!element.isJsonArray) {
            throw InvalidExportSchemaException("Field '$name' must be an array of strings")
        }
        return element.asJsonArray.map { item ->
            if (!item.isJsonPrimitive || !item.asJsonPrimitive.isString) {
                throw InvalidExportSchemaException("Array '$name' must contain string elements")
            }
            item.asString
        }
    }
}
