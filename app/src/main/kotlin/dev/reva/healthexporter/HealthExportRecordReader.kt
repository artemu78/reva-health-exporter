package dev.reva.healthexporter

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.util.concurrent.CancellationException
import kotlin.reflect.KClass

val DEFAULT_EXPORT_TYPES: List<KClass<out Record>> = ExportSourcePolicy.recordTypes

fun interface HealthExportRecordReader {
    suspend fun readRecords(timeWindow: TimeWindow): List<CanonicalRecord>
}

class HealthConnectExportReader(
    private val client: HealthConnectClient,
    private val mapper: HealthRecordMapper = HealthRecordMapper(),
    private val supportedRecordTypes: List<KClass<out Record>> = DEFAULT_EXPORT_TYPES,
    private val pageSize: Int = 1_000,
) : HealthExportRecordReader {

    init {
        require(pageSize > 0) { "pageSize must be positive, got $pageSize" }
    }

    override suspend fun readRecords(timeWindow: TimeWindow): List<CanonicalRecord> {
        val collectedRecords = mutableListOf<CanonicalRecord>()

        for (recordType in supportedRecordTypes) {
            readRecordsForType(recordType, timeWindow, collectedRecords)
        }

        return ExportRecordCanonicalizer.canonicalize(collectedRecords)
    }

    private suspend fun readRecordsForType(
        recordType: KClass<out Record>,
        timeWindow: TimeWindow,
        destination: MutableList<CanonicalRecord>,
    ) {
        var pageToken: String? = null
        val seenPageTokens = mutableSetOf<String>()
        val allowedPackageName = ExportSourcePolicy.allowedPackageName(recordType)

        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = recordType,
                    timeRangeFilter = TimeRangeFilter.between(
                        timeWindow.startInclusive,
                        timeWindow.endExclusive,
                    ),
                    dataOriginFilter = setOf(
                        DataOrigin(allowedPackageName),
                    ),
                    pageSize = pageSize,
                    pageToken = pageToken,
                ),
            )

            extractRecordsFromPage(
                response.records,
                allowedPackageName,
                timeWindow,
                destination,
            )

            pageToken = response.pageToken?.takeIf(String::isNotEmpty)
            if (pageToken != null && !seenPageTokens.add(pageToken)) {
                break
            }
        } while (pageToken != null)
    }

    private fun extractRecordsFromPage(
        records: List<Record>,
        allowedPackageName: String,
        timeWindow: TimeWindow,
        destination: MutableList<CanonicalRecord>,
    ) {
        for (record in records) {
            if (record.metadata.dataOrigin.packageName != allowedPackageName) continue
            val canonical = mapper.mapRecord(record)
            if (!canonical.startTime.isBefore(timeWindow.startInclusive) &&
                canonical.startTime.isBefore(timeWindow.endExclusive)
            ) {
                destination.add(canonical)
            }
        }
    }
}
