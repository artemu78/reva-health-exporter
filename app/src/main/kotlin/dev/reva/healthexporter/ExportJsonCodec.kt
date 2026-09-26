package dev.reva.healthexporter

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal object ExportJsonCodec {
    fun encodeHeader(header: BatchHeader): JsonObject = with(header) {
        JsonObject().apply {
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
            exportDate?.let { addProperty("exportDate", it) }
            exportTimezone?.let { addProperty("exportTimezone", it) }
            dailyIdentity?.let { addProperty("dailyIdentity", it) }
        }
    }

    fun decodeHeader(json: JsonObject): BatchHeader = with(json) {
        val type = optionalString("recordType")
        if (type != null && type != "header") {
            throw InvalidExportSchemaException("Expected recordType 'header', got '$type'")
        }
        val schemaVersion = requiredInt("schemaVersion")
        val installationId = requiredString("installationId")
        val batchId = requiredString("batchId")
        val createdAt = requiredInstant("createdAt")
        val windowElement = get("timeWindow")
            ?: throw InvalidExportSchemaException("Batch header missing 'timeWindow'")
        if (!windowElement.isJsonObject) {
            throw InvalidExportSchemaException("Field 'timeWindow' must be a JSON object")
        }
        val window = windowElement.asJsonObject
        val timeWindow = TimeWindow(
            startInclusive = window.requiredInstant("startInclusive"),
            endExclusive = window.requiredInstant("endExclusive"),
        )
        val recordCount = requiredInt("recordCount")
        val recordTypes = requiredStrings("recordTypes")

        BatchHeader(
            schemaVersion = schemaVersion,
            installationId = installationId,
            batchId = batchId,
            createdAt = createdAt,
            timeWindow = timeWindow,
            recordCount = recordCount,
            recordTypes = recordTypes,
            exportDate = optionalString("exportDate"),
            exportTimezone = optionalString("exportTimezone"),
            dailyIdentity = optionalString("dailyIdentity"),
        )
    }

    fun encodeRecord(record: CanonicalRecord): JsonObject = with(record) {
        JsonObject().apply {
            addProperty("recordType", recordType)
            addProperty("origin", metadata.origin)
            addProperty("startTime", startTime.toString())
            startZoneOffset?.let { addProperty("startZoneOffset", it.toString()) }
            addProperty("endTime", endTime.toString())
            endZoneOffset?.let { addProperty("endZoneOffset", it.toString()) }

            when (record) {
                is CanonicalStepsRecord -> addProperty("count", record.count)
                is CanonicalHeartRateRecord -> add("samples", JsonArray().also { array ->
                    record.samples.forEach { sample ->
                        array.add(JsonObject().apply {
                            addProperty("time", sample.time.toString())
                            addProperty("beatsPerMinute", sample.beatsPerMinute)
                        })
                    }
                })
                is CanonicalDistanceRecord -> addProperty("distanceMeters", record.distanceMeters)
                is CanonicalTotalCaloriesBurnedRecord ->
                    addProperty("energyKilocalories", record.energyKilocalories)
                is CanonicalSleepSessionRecord -> {
                    record.title?.let { addProperty("title", it) }
                    record.notes?.let { addProperty("notes", it) }
                    add("stages", JsonArray().also { array ->
                        record.stages.forEach { stage ->
                            array.add(JsonObject().apply {
                                addProperty("startTime", stage.startTime.toString())
                                addProperty("endTime", stage.endTime.toString())
                                addProperty("stage", stage.stage)
                            })
                        }
                    })
                }
                is CanonicalExerciseSessionRecord -> {
                    addProperty("exerciseType", record.exerciseType)
                    record.title?.let { addProperty("title", it) }
                    record.notes?.let { addProperty("notes", it) }
                    add("segments", JsonArray().also { array ->
                        record.segments.forEach { segment ->
                            array.add(JsonObject().apply {
                                addProperty("startTime", segment.startTime.toString())
                                addProperty("endTime", segment.endTime.toString())
                                addProperty("segmentType", segment.segmentType)
                                addProperty("repetitions", segment.repetitions)
                            })
                        }
                    })
                    add("laps", JsonArray().also { array ->
                        record.laps.forEach { lap ->
                            array.add(JsonObject().apply {
                                addProperty("startTime", lap.startTime.toString())
                                addProperty("endTime", lap.endTime.toString())
                                lap.lengthMeters?.let { addProperty("lengthMeters", it) }
                            })
                        }
                    })
                }
                is CanonicalRestingHeartRateRecord -> addProperty("beatsPerMinute", record.beatsPerMinute)
                is CanonicalOxygenSaturationRecord -> addProperty("percentage", record.percentage)
            }
        }
    }

    fun decodeRecord(json: JsonObject): CanonicalRecord {
        val fields = CommonRecordFields.from(json)
        return when (fields.recordType) {
            CanonicalStepsRecord.TYPE -> fields.steps(json)
            CanonicalHeartRateRecord.TYPE -> fields.heartRate(json)
            CanonicalDistanceRecord.TYPE -> fields.distance(json)
            CanonicalTotalCaloriesBurnedRecord.TYPE -> fields.calories(json)
            CanonicalSleepSessionRecord.TYPE -> fields.sleep(json)
            CanonicalExerciseSessionRecord.TYPE -> fields.exercise(json)
            CanonicalRestingHeartRateRecord.TYPE -> fields.restingHeartRate(json)
            CanonicalOxygenSaturationRecord.TYPE -> fields.oxygenSaturation(json)
            else -> throw InvalidExportSchemaException("Unsupported recordType: '${fields.recordType}'")
        }
    }

    private fun CommonRecordFields.steps(json: JsonObject) = CanonicalStepsRecord(
        startTime = startTime,
        startZoneOffset = startZoneOffset,
        endTime = endTime,
        endZoneOffset = endZoneOffset,
        metadata = metadata,
        count = json.requiredLong("count"),
    )

    private fun CommonRecordFields.heartRate(json: JsonObject): CanonicalHeartRateRecord {
        val samplesElement = json.get("samples")
            ?: throw InvalidExportSchemaException("Missing 'samples' array")
        if (!samplesElement.isJsonArray) {
            throw InvalidExportSchemaException("Field 'samples' must be an array")
        }
        val samples = samplesElement.asJsonArray.map { sampleElement ->
            if (!sampleElement.isJsonObject) {
                throw InvalidExportSchemaException("Heart rate sample must be a JSON object")
            }
            val sample = sampleElement.asJsonObject
            HeartRateSample(
                time = sample.requiredInstant("time"),
                beatsPerMinute = sample.requiredLong("beatsPerMinute"),
            )
        }
        return CanonicalHeartRateRecord(
            startTime = startTime,
            startZoneOffset = startZoneOffset,
            endTime = endTime,
            endZoneOffset = endZoneOffset,
            metadata = metadata,
            samples = samples,
        )
    }

    private fun CommonRecordFields.distance(json: JsonObject) = CanonicalDistanceRecord(
        startTime = startTime,
        startZoneOffset = startZoneOffset,
        endTime = endTime,
        endZoneOffset = endZoneOffset,
        metadata = metadata,
        distanceMeters = json.requiredDouble("distanceMeters"),
    )

    private fun CommonRecordFields.calories(json: JsonObject) = CanonicalTotalCaloriesBurnedRecord(
        startTime = startTime,
        startZoneOffset = startZoneOffset,
        endTime = endTime,
        endZoneOffset = endZoneOffset,
        metadata = metadata,
        energyKilocalories = json.requiredDouble("energyKilocalories"),
    )

    private fun CommonRecordFields.sleep(json: JsonObject): CanonicalSleepSessionRecord {
        val stagesElement = json.get("stages")
        val stages = when {
            stagesElement == null || stagesElement.isJsonNull -> emptyList()
            stagesElement.isJsonArray -> stagesElement.asJsonArray.map { stageElement ->
                if (!stageElement.isJsonObject) {
                    throw InvalidExportSchemaException("Sleep stage must be a JSON object")
                }
                val stage = stageElement.asJsonObject
                SleepStage(
                    startTime = stage.requiredInstant("startTime"),
                    endTime = stage.requiredInstant("endTime"),
                    stage = stage.requiredInt("stage"),
                )
            }
            else -> throw InvalidExportSchemaException("Field 'stages' must be an array")
        }
        return CanonicalSleepSessionRecord(
            startTime = startTime,
            startZoneOffset = startZoneOffset,
            endTime = endTime,
            endZoneOffset = endZoneOffset,
            metadata = metadata,
            title = json.optionalString("title"),
            notes = json.optionalString("notes"),
            stages = stages,
        )
    }

    private fun CommonRecordFields.exercise(json: JsonObject): CanonicalExerciseSessionRecord {
        val segments = decodeExerciseSegments(json)
        val laps = decodeExerciseLaps(json)
        return CanonicalExerciseSessionRecord(
            startTime = startTime,
            startZoneOffset = startZoneOffset,
            endTime = endTime,
            endZoneOffset = endZoneOffset,
            metadata = metadata,
            exerciseType = json.requiredInt("exerciseType"),
            title = json.optionalString("title"),
            notes = json.optionalString("notes"),
            segments = segments,
            laps = laps,
        )
    }

    private fun decodeExerciseSegments(json: JsonObject): List<ExerciseSegmentModel> {
        val segmentsElement = json.get("segments")
        return when {
            segmentsElement == null || segmentsElement.isJsonNull -> emptyList()
            segmentsElement.isJsonArray -> segmentsElement.asJsonArray.map { segmentElement ->
                if (!segmentElement.isJsonObject) {
                    throw InvalidExportSchemaException("Exercise segment must be a JSON object")
                }
                val segment = segmentElement.asJsonObject
                ExerciseSegmentModel(
                    startTime = segment.requiredInstant("startTime"),
                    endTime = segment.requiredInstant("endTime"),
                    segmentType = segment.requiredInt("segmentType"),
                    repetitions = segment.optionalInt("repetitions") ?: 0,
                )
            }
            else -> throw InvalidExportSchemaException("Field 'segments' must be an array")
        }
    }

    private fun decodeExerciseLaps(json: JsonObject): List<ExerciseLapModel> {
        val lapsElement = json.get("laps")
        return when {
            lapsElement == null || lapsElement.isJsonNull -> emptyList()
            lapsElement.isJsonArray -> lapsElement.asJsonArray.map { lapElement ->
                if (!lapElement.isJsonObject) {
                    throw InvalidExportSchemaException("Exercise lap must be a JSON object")
                }
                val lap = lapElement.asJsonObject
                ExerciseLapModel(
                    startTime = lap.requiredInstant("startTime"),
                    endTime = lap.requiredInstant("endTime"),
                    lengthMeters = lap.optionalDouble("lengthMeters"),
                )
            }
            else -> throw InvalidExportSchemaException("Field 'laps' must be an array")
        }
    }

    private fun CommonRecordFields.restingHeartRate(json: JsonObject) = CanonicalRestingHeartRateRecord(
        startTime = startTime,
        startZoneOffset = startZoneOffset,
        endTime = endTime,
        endZoneOffset = endZoneOffset,
        metadata = metadata,
        beatsPerMinute = json.requiredLong("beatsPerMinute"),
    )

    private fun CommonRecordFields.oxygenSaturation(json: JsonObject) = CanonicalOxygenSaturationRecord(
        startTime = startTime,
        startZoneOffset = startZoneOffset,
        endTime = endTime,
        endZoneOffset = endZoneOffset,
        metadata = metadata,
        percentage = json.requiredDouble("percentage"),
    )

    private data class CommonRecordFields(
        val recordType: String,
        val startTime: java.time.Instant,
        val startZoneOffset: java.time.ZoneOffset?,
        val endTime: java.time.Instant,
        val endZoneOffset: java.time.ZoneOffset?,
        val metadata: RecordMetadata,
    ) {
        companion object {
            fun from(json: JsonObject): CommonRecordFields {
                val recordType = json.requiredString("recordType")
                val origin = json.requiredString("origin")
                val startTime = json.requiredInstant("startTime")
                val startZoneOffset = json.optionalZoneOffset("startZoneOffset")
                val endTime = json.requiredInstant("endTime")
                val endZoneOffset = json.optionalZoneOffset("endZoneOffset")
                val recordId = json.optionalString("recordId")
                val clientRecordId = json.optionalString("clientRecordId")
                val clientRecordVersion = json.optionalLong("clientRecordVersion")
                val recordingMethod = json.optionalInt("recordingMethod")
                val deviceElement = json.get("device")
                val device = when {
                    deviceElement == null || deviceElement.isJsonNull -> null
                    deviceElement.isJsonObject -> deviceElement.asJsonObject.let { deviceJson ->
                        DeviceMetadata(
                            manufacturer = deviceJson.optionalString("manufacturer"),
                            model = deviceJson.optionalString("model"),
                            type = deviceJson.optionalInt("type"),
                        )
                    }
                    else -> throw InvalidExportSchemaException("Field 'device' must be a JSON object")
                }
                val lastModifiedTime = json.optionalInstant("lastModifiedTime")
                return CommonRecordFields(
                    recordType = recordType,
                    startTime = startTime,
                    startZoneOffset = startZoneOffset,
                    endTime = endTime,
                    endZoneOffset = endZoneOffset,
                    metadata = RecordMetadata(
                        recordId = recordId,
                        origin = origin,
                        clientRecordId = clientRecordId,
                        clientRecordVersion = clientRecordVersion,
                        recordingMethod = recordingMethod,
                        device = device,
                        lastModifiedTime = lastModifiedTime,
                    ),
                )
            }
        }
    }
}
