package dev.reva.healthexporter

import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import java.time.Instant
import java.time.ZoneOffset

class InvalidExportSchemaException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

data class TimeWindow(
    val startInclusive: Instant,
    val endExclusive: Instant,
) {
    init {
        if (!startInclusive.isBefore(endExclusive)) {
            throw InvalidExportSchemaException(
                "Time window startInclusive ($startInclusive) must be strictly before endExclusive ($endExclusive)",
            )
        }
    }
}

data class DeviceMetadata(
    val manufacturer: String? = null,
    val model: String? = null,
    val type: Int? = null,
)

data class RecordMetadata(
    val recordId: String,
    val origin: String,
    val clientRecordId: String? = null,
    val clientRecordVersion: Long? = null,
    val recordingMethod: Int? = null,
    val device: DeviceMetadata? = null,
    val lastModifiedTime: Instant? = null,
) {
    init {
        if (recordId.isBlank()) {
            throw InvalidExportSchemaException("Record ID must not be blank")
        }
        if (origin.isBlank()) {
            throw InvalidExportSchemaException("Data origin package must not be blank")
        }
    }
}

sealed interface CanonicalRecord {
    val recordType: String
    val metadata: RecordMetadata
    val startTime: Instant
    val startZoneOffset: ZoneOffset?
    val endTime: Instant
    val endZoneOffset: ZoneOffset?

    fun validate() {
        if (startTime.isAfter(endTime)) {
            throw InvalidExportSchemaException(
                "Record startTime ($startTime) must not be after endTime ($endTime)",
            )
        }
    }
}

data class CanonicalStepsRecord(
    override val startTime: Instant,
    override val startZoneOffset: ZoneOffset?,
    override val endTime: Instant,
    override val endZoneOffset: ZoneOffset?,
    override val metadata: RecordMetadata,
    val count: Long,
) : CanonicalRecord {
    override val recordType: String get() = TYPE

    init {
        validate()
        if (count < 0) {
            throw InvalidExportSchemaException("Step count must be non-negative, got $count")
        }
    }

    companion object {
        const val TYPE = "steps"
    }
}

data class HeartRateSample(
    val time: Instant,
    val beatsPerMinute: Long,
) {
    init {
        if (beatsPerMinute !in 1L..300L) {
            throw InvalidExportSchemaException(
                "Heart rate beatsPerMinute must be in range 1..300, got $beatsPerMinute",
            )
        }
    }
}

data class CanonicalHeartRateRecord(
    override val startTime: Instant,
    override val startZoneOffset: ZoneOffset?,
    override val endTime: Instant,
    override val endZoneOffset: ZoneOffset?,
    override val metadata: RecordMetadata,
    val samples: List<HeartRateSample>,
) : CanonicalRecord {
    override val recordType: String get() = TYPE

    init {
        validate()
        samples.forEach { sample ->
            if (sample.time.isBefore(startTime) || sample.time.isAfter(endTime)) {
                throw InvalidExportSchemaException(
                    "Heart rate sample time (${sample.time}) outside record bounds ($startTime..$endTime)",
                )
            }
        }
    }

    companion object {
        const val TYPE = "heart_rate"
    }
}

data class CanonicalDistanceRecord(
    override val startTime: Instant,
    override val startZoneOffset: ZoneOffset?,
    override val endTime: Instant,
    override val endZoneOffset: ZoneOffset?,
    override val metadata: RecordMetadata,
    val distanceMeters: Double,
) : CanonicalRecord {
    override val recordType: String get() = TYPE

    init {
        validate()
        if (distanceMeters < 0.0 || distanceMeters.isNaN() || distanceMeters.isInfinite()) {
            throw InvalidExportSchemaException("Distance meters must be finite and non-negative, got $distanceMeters")
        }
    }

    companion object {
        const val TYPE = "distance"
    }
}

data class CanonicalTotalCaloriesBurnedRecord(
    override val startTime: Instant,
    override val startZoneOffset: ZoneOffset?,
    override val endTime: Instant,
    override val endZoneOffset: ZoneOffset?,
    override val metadata: RecordMetadata,
    val energyKilocalories: Double,
) : CanonicalRecord {
    override val recordType: String get() = TYPE

    init {
        validate()
        if (energyKilocalories < 0.0 || energyKilocalories.isNaN() || energyKilocalories.isInfinite()) {
            throw InvalidExportSchemaException(
                "Energy kilocalories must be finite and non-negative, got $energyKilocalories",
            )
        }
    }

    companion object {
        const val TYPE = "total_calories_burned"
    }
}

data class SleepStage(
    val startTime: Instant,
    val endTime: Instant,
    val stage: Int,
) {
    init {
        if (startTime.isAfter(endTime)) {
            throw InvalidExportSchemaException(
                "Sleep stage startTime ($startTime) must not be after endTime ($endTime)",
            )
        }
    }
}

data class CanonicalSleepSessionRecord(
    override val startTime: Instant,
    override val startZoneOffset: ZoneOffset?,
    override val endTime: Instant,
    override val endZoneOffset: ZoneOffset?,
    override val metadata: RecordMetadata,
    val title: String? = null,
    val notes: String? = null,
    val stages: List<SleepStage> = emptyList(),
) : CanonicalRecord {
    override val recordType: String get() = TYPE

    init {
        validate()
        stages.forEach { stage ->
            if (stage.startTime.isBefore(startTime) || stage.endTime.isAfter(endTime)) {
                throw InvalidExportSchemaException(
                    "Sleep stage interval (${stage.startTime}..${stage.endTime}) outside session bounds ($startTime..$endTime)",
                )
            }
        }
    }

    companion object {
        const val TYPE = "sleep_session"
    }
}

data class ExerciseSegmentModel(
    val startTime: Instant,
    val endTime: Instant,
    val segmentType: Int,
    val repetitions: Int = 0,
) {
    init {
        if (startTime.isAfter(endTime)) {
            throw InvalidExportSchemaException(
                "Exercise segment startTime ($startTime) must not be after endTime ($endTime)",
            )
        }
        if (repetitions < 0) {
            throw InvalidExportSchemaException("Exercise segment repetitions must be non-negative, got $repetitions")
        }
    }
}

data class ExerciseLapModel(
    val startTime: Instant,
    val endTime: Instant,
    val lengthMeters: Double? = null,
) {
    init {
        if (startTime.isAfter(endTime)) {
            throw InvalidExportSchemaException(
                "Exercise lap startTime ($startTime) must not be after endTime ($endTime)",
            )
        }
        if (lengthMeters != null && (lengthMeters < 0.0 || lengthMeters.isNaN() || lengthMeters.isInfinite())) {
            throw InvalidExportSchemaException("Exercise lap length must be finite and non-negative, got $lengthMeters")
        }
    }
}

data class CanonicalExerciseSessionRecord(
    override val startTime: Instant,
    override val startZoneOffset: ZoneOffset?,
    override val endTime: Instant,
    override val endZoneOffset: ZoneOffset?,
    override val metadata: RecordMetadata,
    val exerciseType: Int,
    val title: String? = null,
    val notes: String? = null,
    val segments: List<ExerciseSegmentModel> = emptyList(),
    val laps: List<ExerciseLapModel> = emptyList(),
) : CanonicalRecord {
    override val recordType: String get() = TYPE

    init {
        validate()
        segments.forEach { segment ->
            if (segment.startTime.isBefore(startTime) || segment.endTime.isAfter(endTime)) {
                throw InvalidExportSchemaException(
                    "Exercise segment interval (${segment.startTime}..${segment.endTime}) outside session bounds ($startTime..$endTime)",
                )
            }
        }
        laps.forEach { lap ->
            if (lap.startTime.isBefore(startTime) || lap.endTime.isAfter(endTime)) {
                throw InvalidExportSchemaException(
                    "Exercise lap interval (${lap.startTime}..${lap.endTime}) outside session bounds ($startTime..$endTime)",
                )
            }
        }
    }

    companion object {
        const val TYPE = "exercise_session"
    }
}

data class CanonicalRestingHeartRateRecord(
    override val startTime: Instant,
    override val startZoneOffset: ZoneOffset?,
    override val endTime: Instant,
    override val endZoneOffset: ZoneOffset?,
    override val metadata: RecordMetadata,
    val beatsPerMinute: Long,
) : CanonicalRecord {
    override val recordType: String get() = TYPE

    init {
        validate()
        if (beatsPerMinute !in 1L..300L) {
            throw InvalidExportSchemaException(
                "Resting heart rate beatsPerMinute must be in range 1..300, got $beatsPerMinute",
            )
        }
    }

    companion object {
        const val TYPE = "resting_heart_rate"
    }
}

data class CanonicalOxygenSaturationRecord(
    override val startTime: Instant,
    override val startZoneOffset: ZoneOffset?,
    override val endTime: Instant,
    override val endZoneOffset: ZoneOffset?,
    override val metadata: RecordMetadata,
    val percentage: Double,
) : CanonicalRecord {
    override val recordType: String get() = TYPE

    init {
        validate()
        if (percentage !in 0.0..100.0 || percentage.isNaN()) {
            throw InvalidExportSchemaException("Oxygen saturation percentage must be within 0.0..100.0, got $percentage")
        }
    }

    companion object {
        const val TYPE = "oxygen_saturation"
    }
}

data class BatchHeader(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val installationId: String,
    val batchId: String,
    val createdAt: Instant,
    val timeWindow: TimeWindow,
    val recordCount: Int,
    val recordTypes: List<String>,
) {
    init {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw InvalidExportSchemaException(
                "Unsupported schemaVersion: $schemaVersion (expected $CURRENT_SCHEMA_VERSION)",
            )
        }
        if (installationId.isBlank()) {
            throw InvalidExportSchemaException("installationId must not be blank")
        }
        if (batchId.isBlank()) {
            throw InvalidExportSchemaException("batchId must not be blank")
        }
        if (recordCount < 0) {
            throw InvalidExportSchemaException("recordCount must be non-negative, got $recordCount")
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

data class ExportBatch(
    val header: BatchHeader,
    val records: List<CanonicalRecord>,
) {
    init {
        if (header.recordCount != records.size) {
            throw InvalidExportSchemaException(
                "Batch header recordCount (${header.recordCount}) does not match actual records size (${records.size})",
            )
        }
        val actualTypes = records.map(CanonicalRecord::recordType).distinct().sorted()
        val expectedTypes = header.recordTypes.sorted()
        if (actualTypes != expectedTypes) {
            throw InvalidExportSchemaException(
                "Batch header recordTypes ($expectedTypes) does not match actual record types ($actualTypes)",
            )
        }
    }
}

class HealthRecordMapper {
    fun mapRecord(record: Record): CanonicalRecord = when (record) {
        is StepsRecord -> mapSteps(record)
        is HeartRateRecord -> mapHeartRate(record)
        is DistanceRecord -> mapDistance(record)
        is TotalCaloriesBurnedRecord -> mapTotalCaloriesBurned(record)
        is SleepSessionRecord -> mapSleepSession(record)
        is ExerciseSessionRecord -> mapExerciseSession(record)
        is RestingHeartRateRecord -> mapRestingHeartRate(record)
        is OxygenSaturationRecord -> mapOxygenSaturation(record)
        else -> throw InvalidExportSchemaException("Unsupported Health Connect record type: ${record::class.qualifiedName}")
    }

    fun mapSteps(record: StepsRecord): CanonicalStepsRecord = CanonicalStepsRecord(
        startTime = record.startTime,
        startZoneOffset = record.startZoneOffset,
        endTime = record.endTime,
        endZoneOffset = record.endZoneOffset,
        metadata = record.metadata.toRecordMetadata(),
        count = record.count,
    )

    fun mapHeartRate(record: HeartRateRecord): CanonicalHeartRateRecord = CanonicalHeartRateRecord(
        startTime = record.startTime,
        startZoneOffset = record.startZoneOffset,
        endTime = record.endTime,
        endZoneOffset = record.endZoneOffset,
        metadata = record.metadata.toRecordMetadata(),
        samples = record.samples.map { HeartRateSample(it.time, it.beatsPerMinute) },
    )

    fun mapDistance(record: DistanceRecord): CanonicalDistanceRecord = CanonicalDistanceRecord(
        startTime = record.startTime,
        startZoneOffset = record.startZoneOffset,
        endTime = record.endTime,
        endZoneOffset = record.endZoneOffset,
        metadata = record.metadata.toRecordMetadata(),
        distanceMeters = record.distance.inMeters,
    )

    fun mapTotalCaloriesBurned(record: TotalCaloriesBurnedRecord): CanonicalTotalCaloriesBurnedRecord =
        CanonicalTotalCaloriesBurnedRecord(
            startTime = record.startTime,
            startZoneOffset = record.startZoneOffset,
            endTime = record.endTime,
            endZoneOffset = record.endZoneOffset,
            metadata = record.metadata.toRecordMetadata(),
            energyKilocalories = record.energy.inKilocalories,
        )

    fun mapSleepSession(record: SleepSessionRecord): CanonicalSleepSessionRecord = CanonicalSleepSessionRecord(
        startTime = record.startTime,
        startZoneOffset = record.startZoneOffset,
        endTime = record.endTime,
        endZoneOffset = record.endZoneOffset,
        metadata = record.metadata.toRecordMetadata(),
        title = record.title,
        notes = record.notes,
        stages = record.stages.map { SleepStage(it.startTime, it.endTime, it.stage) },
    )

    fun mapExerciseSession(record: ExerciseSessionRecord): CanonicalExerciseSessionRecord =
        CanonicalExerciseSessionRecord(
            startTime = record.startTime,
            startZoneOffset = record.startZoneOffset,
            endTime = record.endTime,
            endZoneOffset = record.endZoneOffset,
            metadata = record.metadata.toRecordMetadata(),
            exerciseType = record.exerciseType,
            title = record.title,
            notes = record.notes,
            segments = record.segments.map {
                ExerciseSegmentModel(
                    startTime = it.startTime,
                    endTime = it.endTime,
                    segmentType = it.segmentType,
                    repetitions = it.repetitions,
                )
            },
            laps = record.laps.map {
                ExerciseLapModel(
                    startTime = it.startTime,
                    endTime = it.endTime,
                    lengthMeters = it.length?.inMeters,
                )
            },
        )

    fun mapRestingHeartRate(record: RestingHeartRateRecord): CanonicalRestingHeartRateRecord =
        CanonicalRestingHeartRateRecord(
            startTime = record.time,
            startZoneOffset = record.zoneOffset,
            endTime = record.time,
            endZoneOffset = record.zoneOffset,
            metadata = record.metadata.toRecordMetadata(),
            beatsPerMinute = record.beatsPerMinute,
        )

    fun mapOxygenSaturation(record: OxygenSaturationRecord): CanonicalOxygenSaturationRecord =
        CanonicalOxygenSaturationRecord(
            startTime = record.time,
            startZoneOffset = record.zoneOffset,
            endTime = record.time,
            endZoneOffset = record.zoneOffset,
            metadata = record.metadata.toRecordMetadata(),
            percentage = record.percentage.value,
        )

    private fun Metadata.toRecordMetadata(): RecordMetadata {
        val recordId = id.takeIf(String::isNotBlank)
            ?: clientRecordId?.takeIf(String::isNotBlank)
            ?: throw InvalidExportSchemaException("Record must have either id or clientRecordId")
        val originPackage = dataOrigin.packageName.takeIf(String::isNotBlank)
            ?: throw InvalidExportSchemaException("Record dataOrigin must not be blank")

        val resolvedClientRecordId = clientRecordId?.takeIf(String::isNotBlank)
        val resolvedClientRecordVersion = if (resolvedClientRecordId != null) {
            clientRecordVersion
        } else {
            null
        }

        return RecordMetadata(
            recordId = recordId,
            origin = originPackage,
            clientRecordId = resolvedClientRecordId,
            clientRecordVersion = resolvedClientRecordVersion,
            recordingMethod = recordingMethod.takeIf { it != Metadata.RECORDING_METHOD_UNKNOWN },
            device = device?.let { dev ->
                DeviceMetadata(
                    manufacturer = dev.manufacturer?.takeIf(String::isNotBlank),
                    model = dev.model?.takeIf(String::isNotBlank),
                    type = dev.type.takeIf { it != Device.TYPE_UNKNOWN },
                )
            },
            lastModifiedTime = lastModifiedTime.takeIf { it != Instant.EPOCH },
        )
    }
}
