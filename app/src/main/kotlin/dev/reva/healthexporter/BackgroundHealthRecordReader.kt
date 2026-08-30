package dev.reva.healthexporter

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.io.IOException
import java.time.ZoneId
import java.util.concurrent.CancellationException
import kotlin.reflect.KClass

class BackgroundHealthRecordReader(
    private val client: HealthConnectClient,
    private val clock: DiagnosticClock = SystemDiagnosticClock,
    private val pageSize: Int = 1_000,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    init {
        require(pageSize > 0) { "Page size must be positive" }
    }

    suspend fun readConfirmedRecords(window: ProbeTimeWindow): BackgroundReadExecutionSummary {
        val executionInstant = clock.now(zoneId).toInstant()
        val targetTypes = listOf<KClass<out Record>>(
            StepsRecord::class,
            HeartRateRecord::class,
            DistanceRecord::class,
            TotalCaloriesBurnedRecord::class,
            SleepSessionRecord::class,
        )

        var totalRecords = 0
        val dataOrigins = mutableSetOf<String>()
        var readTypesCount = 0

        for (recordType in targetTypes) {
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
                    val pageRecords = response.records
                    totalRecords += pageRecords.size
                    for (record in pageRecords) {
                        val pkg = record.metadata.dataOrigin.packageName
                        if (pkg.isNotBlank()) {
                            dataOrigins += pkg
                        }
                    }
                    pageToken = response.pageToken?.takeIf(String::isNotEmpty)
                    if (pageToken != null && !seenPageTokens.add(pageToken)) {
                        return BackgroundReadExecutionSummary(
                            outcome = BackgroundReadOutcome.RETRYABLE_FAILURE,
                            message = "Repeated page token received during Health Connect read.",
                            totalRecords = totalRecords,
                            readTypesCount = readTypesCount,
                            executionTimestamp = executionInstant,
                            dataOrigins = dataOrigins,
                        )
                    }
                } while (pageToken != null)
                readTypesCount++
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (security: SecurityException) {
                return BackgroundReadExecutionSummary(
                    outcome = BackgroundReadOutcome.USER_ACTION_REQUIRED,
                    message = "Health Connect permission missing or revoked: ${security.message ?: "permission denied"}",
                    totalRecords = totalRecords,
                    readTypesCount = readTypesCount,
                    executionTimestamp = executionInstant,
                    dataOrigins = dataOrigins,
                )
            } catch (unsupported: UnsupportedOperationException) {
                return BackgroundReadExecutionSummary(
                    outcome = BackgroundReadOutcome.UNSUPPORTED,
                    message = "Background reads are unsupported by Health Connect: ${unsupported.message ?: "unsupported"}",
                    totalRecords = totalRecords,
                    readTypesCount = readTypesCount,
                    executionTimestamp = executionInstant,
                    dataOrigins = dataOrigins,
                )
            } catch (io: IOException) {
                return BackgroundReadExecutionSummary(
                    outcome = BackgroundReadOutcome.RETRYABLE_FAILURE,
                    message = "Transient I/O failure reading Health Connect records: ${io.message ?: "read error"}",
                    totalRecords = totalRecords,
                    readTypesCount = readTypesCount,
                    executionTimestamp = executionInstant,
                    dataOrigins = dataOrigins,
                )
            } catch (other: Exception) {
                return BackgroundReadExecutionSummary(
                    outcome = BackgroundReadOutcome.RETRYABLE_FAILURE,
                    message = "Failed reading Health Connect records: ${other.message ?: "unknown error"}",
                    totalRecords = totalRecords,
                    readTypesCount = readTypesCount,
                    executionTimestamp = executionInstant,
                    dataOrigins = dataOrigins,
                )
            }
        }

        return BackgroundReadExecutionSummary(
            outcome = BackgroundReadOutcome.SUCCESS,
            message = "Successfully read $totalRecords records across $readTypesCount confirmed record types.",
            totalRecords = totalRecords,
            readTypesCount = readTypesCount,
            executionTimestamp = executionInstant,
            dataOrigins = dataOrigins,
        )
    }
}
