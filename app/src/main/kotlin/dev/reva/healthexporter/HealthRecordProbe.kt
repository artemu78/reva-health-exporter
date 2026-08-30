package dev.reva.healthexporter

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.CancellationException
import kotlin.reflect.KClass

data class ProbeTimeWindow(
    val startInclusive: Instant,
    val endExclusive: Instant,
) {
    init {
        require(startInclusive.isBefore(endExclusive)) {
            "Probe time window start must be before its end"
        }
    }

    companion object {
        fun previousLocalDays(endExclusive: ZonedDateTime, days: Int): ProbeTimeWindow {
            require(days > 0) { "Number of local days must be positive" }
            return ProbeTimeWindow(
                startInclusive = endExclusive.minusDays(days.toLong()).toInstant(),
                endExclusive = endExclusive.toInstant(),
            )
        }
    }
}

enum class MetricProbeStatus {
    POPULATED,
    EMPTY,
    PERMISSION_MISSING,
    UNSUPPORTED,
    FAILED,
}

data class MetricProbeSummary(
    val metric: HealthMetric,
    val status: MetricProbeStatus,
    val count: Int = 0,
    val oldestTimestamp: Instant? = null,
    val newestTimestamp: Instant? = null,
    val dataOrigins: Set<String> = emptySet(),
    val previews: List<MetricRecordPreview> = emptyList(),
)

data class MetricRecordPreview(
    val startTimestamp: Instant,
    val endTimestamp: Instant,
    val dataOrigin: String?,
)

data class DiagnosticProbeResult(
    val window: ProbeTimeWindow,
    val summaries: Map<HealthMetric, MetricProbeSummary>,
)

class HealthRecordProbe(
    private val client: HealthConnectClient,
    private val pageSize: Int = 1_000,
) {
    init {
        require(pageSize > 0) { "Page size must be positive" }
    }

    suspend fun probe(
        window: ProbeTimeWindow,
        grantedPermissions: Set<String>,
    ): DiagnosticProbeResult {
        val summaries = listOf(
            probeRecords(
                HealthMetric.STEPS,
                StepsRecord::class,
                window,
                grantedPermissions,
                StepsRecord::startTime,
                StepsRecord::endTime,
            ),
            probeRecords(
                HealthMetric.HEART_RATE,
                HeartRateRecord::class,
                window,
                grantedPermissions,
                HeartRateRecord::startTime,
                HeartRateRecord::endTime,
            ),
            probeRecords(
                HealthMetric.RESTING_HEART_RATE,
                RestingHeartRateRecord::class,
                window,
                grantedPermissions,
                RestingHeartRateRecord::time,
                RestingHeartRateRecord::time,
            ),
            probeRecords(
                HealthMetric.SLEEP,
                SleepSessionRecord::class,
                window,
                grantedPermissions,
                SleepSessionRecord::startTime,
                SleepSessionRecord::endTime,
            ),
            probeRecords(
                HealthMetric.DISTANCE,
                DistanceRecord::class,
                window,
                grantedPermissions,
                DistanceRecord::startTime,
                DistanceRecord::endTime,
            ),
            probeRecords(
                HealthMetric.TOTAL_CALORIES_BURNED,
                TotalCaloriesBurnedRecord::class,
                window,
                grantedPermissions,
                TotalCaloriesBurnedRecord::startTime,
                TotalCaloriesBurnedRecord::endTime,
            ),
            probeRecords(
                HealthMetric.EXERCISE_SESSIONS,
                ExerciseSessionRecord::class,
                window,
                grantedPermissions,
                ExerciseSessionRecord::startTime,
                ExerciseSessionRecord::endTime,
            ),
            probeRecords(
                HealthMetric.OXYGEN_SATURATION,
                OxygenSaturationRecord::class,
                window,
                grantedPermissions,
                OxygenSaturationRecord::time,
                OxygenSaturationRecord::time,
            ),
        )
        return DiagnosticProbeResult(window, summaries.associateBy(MetricProbeSummary::metric))
    }

    private suspend fun <T : Record> probeRecords(
        metric: HealthMetric,
        recordType: KClass<T>,
        window: ProbeTimeWindow,
        grantedPermissions: Set<String>,
        startTimestamp: (T) -> Instant,
        endTimestamp: (T) -> Instant,
    ): MetricProbeSummary {
        val permission = HealthConnectConfiguration.permissionByMetric.getValue(metric)
        if (permission !in grantedPermissions) {
            return MetricProbeSummary(metric, MetricProbeStatus.PERMISSION_MISSING)
        }
        val records = mutableListOf<T>()
        try {
            var pageToken: String? = null
            val seenPageTokens = mutableSetOf<String>()
            do {
                val response = client.readRecords(
                    ReadRecordsRequest(
                        recordType = recordType,
                        timeRangeFilter = TimeRangeFilter.between(
                            window.startInclusive,
                            window.endExclusive,
                        ),
                        pageSize = pageSize,
                        pageToken = pageToken,
                    ),
                )
                records += response.records
                pageToken = response.pageToken?.takeIf(String::isNotEmpty)
                if (pageToken != null && !seenPageTokens.add(pageToken)) {
                    return records.toSummary(
                        metric,
                        MetricProbeStatus.FAILED,
                        startTimestamp,
                        endTimestamp,
                    )
                }
            } while (pageToken != null)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: UnsupportedOperationException) {
            return records.toSummary(metric, MetricProbeStatus.UNSUPPORTED, startTimestamp, endTimestamp)
        } catch (_: SecurityException) {
            return records.toSummary(
                metric,
                MetricProbeStatus.PERMISSION_MISSING,
                startTimestamp,
                endTimestamp,
            )
        } catch (_: Exception) {
            return records.toSummary(metric, MetricProbeStatus.FAILED, startTimestamp, endTimestamp)
        }
        val status = if (records.isEmpty()) MetricProbeStatus.EMPTY else MetricProbeStatus.POPULATED
        return records.toSummary(metric, status, startTimestamp, endTimestamp)
    }

    private fun <T : Record> List<T>.toSummary(
        metric: HealthMetric,
        status: MetricProbeStatus,
        startTimestamp: (T) -> Instant,
        endTimestamp: (T) -> Instant,
    ): MetricProbeSummary = MetricProbeSummary(
        metric = metric,
        status = status,
        count = size,
        oldestTimestamp = minOfOrNull(startTimestamp),
        newestTimestamp = maxOfOrNull(endTimestamp),
        dataOrigins = map { it.metadata.dataOrigin.packageName }
            .filter(String::isNotBlank)
            .toSet(),
        previews = sortedByDescending(endTimestamp)
            .take(MAX_PREVIEW_RECORDS)
            .map { record ->
                MetricRecordPreview(
                    startTimestamp = startTimestamp(record),
                    endTimestamp = endTimestamp(record),
                    dataOrigin = record.metadata.dataOrigin.packageName.takeIf(String::isNotBlank),
                )
            },
    )

    private companion object {
        const val MAX_PREVIEW_RECORDS = 3
    }
}
