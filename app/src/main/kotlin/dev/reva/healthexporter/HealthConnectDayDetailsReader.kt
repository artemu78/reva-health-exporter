package dev.reva.healthexporter

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter

/** Unlike the export reader, inspection intentionally retains every readable data origin. */
class HealthConnectDayDetailsReader(
    private val client: HealthConnectClient,
    private val permissions: Set<String>,
    private val pageSize: Int = 1_000,
) : DayDetailsReader {
    init { require(pageSize > 0) }

    override suspend fun read(type: DetailType, window: TimeWindow): List<CanonicalRecord> {
        val recordType = when (type) {
            DetailType.STEPS -> StepsRecord::class
            DetailType.SLEEP -> SleepSessionRecord::class
            DetailType.WORKOUTS -> ExerciseSessionRecord::class
            DetailType.HEART_RATE -> HeartRateRecord::class
            DetailType.RESTING_HEART_RATE -> RestingHeartRateRecord::class
            DetailType.DISTANCE -> DistanceRecord::class
            DetailType.CALORIES -> TotalCaloriesBurnedRecord::class
            DetailType.OXYGEN -> OxygenSaturationRecord::class
            DetailType.EMA -> return emptyList()
        }
        if (HealthPermission.getReadPermission(recordType) !in permissions) throw SecurityException()
        val records = mutableListOf<CanonicalRecord>()
        val mapper = HealthRecordMapper()
        var token: String? = null
        val tokens = mutableSetOf<String>()
        do {
            val page = client.readRecords(ReadRecordsRequest(
                recordType = recordType,
                timeRangeFilter = TimeRangeFilter.between(window.startInclusive, window.endExclusive),
                pageSize = pageSize,
                pageToken = token,
            ))
            records += page.records.map(mapper::mapRecord)
            token = page.pageToken?.takeIf { it.isNotEmpty() }
            check(token == null || tokens.add(token)) { "Repeated Health Connect page token" }
        } while (token != null)
        return records.filter { it.startTime >= window.startInclusive && it.startTime < window.endExclusive }
    }
}
