package dev.reva.healthexporter

import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.testing.FakeHealthConnectClient
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportHistoryTest {
    private val moscow = ZoneId.of("Europe/Moscow")

    @Test
    fun coverageClassifiesUploadedPartialMissingPendingAndUnknownDays() {
        val day = LocalDate.parse("2026-08-30")
        val window = localDayWindow(day, moscow)
        assertEquals(DayCoverage.UPLOADED, classifyDayCoverage(window, listOf(entry("a", window, HistoryBatchStatus.CONFIRMED))))
        assertEquals(
            DayCoverage.PARTIALLY_UPLOADED,
            classifyDayCoverage(window, listOf(entry("b", TimeWindow(window.startInclusive, window.startInclusive.plusSeconds(3600)), HistoryBatchStatus.CONFIRMED))),
        )
        assertEquals(DayCoverage.NOT_UPLOADED, classifyDayCoverage(window, emptyList()))
        assertEquals(DayCoverage.PENDING_RETRYING, classifyDayCoverage(window, listOf(entry("c", window, HistoryBatchStatus.PENDING))))
        assertEquals(DayCoverage.UNKNOWN, classifyDayCoverage(window, emptyList(), inventoryKnown = false))
    }

    @Test
    fun adjacentConfirmedBatchesCombineExactlyAcrossDayBoundaries() {
        val window = localDayWindow(LocalDate.parse("2026-08-30"), moscow)
        val middle = window.startInclusive.plusSeconds(12 * 3600)
        val entries = listOf(
            entry("first", TimeWindow(window.startInclusive, middle), HistoryBatchStatus.CONFIRMED),
            entry("second", TimeWindow(middle, window.endExclusive), HistoryBatchStatus.CONFIRMED),
        )
        assertEquals(DayCoverage.UPLOADED, classifyDayCoverage(window, entries))
    }

    @Test
    fun localDayWindowsHandleOffsetsDstAndTimezoneChanges() {
        assertEquals(24, localDayWindow(LocalDate.parse("2026-08-30"), moscow).duration.toHours())
        assertEquals(23, localDayWindow(LocalDate.parse("2026-03-29"), ZoneId.of("Europe/Berlin")).duration.toHours())
        assertEquals(25, localDayWindow(LocalDate.parse("2026-10-25"), ZoneId.of("Europe/Berlin")).duration.toHours())
        assertEquals(
            Instant.parse("2026-08-29T21:00:00Z"),
            localDayWindow(LocalDate.parse("2026-08-30"), moscow).startInclusive,
        )
        assertEquals(
            Instant.parse("2026-08-30T07:00:00Z"),
            localDayWindow(LocalDate.parse("2026-08-30"), ZoneId.of("America/Los_Angeles")).startInclusive,
        )
    }

    @Test
    fun malformedInventoryMakesCoverageUnknownAndScopesStayIsolated() {
        val store = InMemoryExportHistoryStore()
        val window = localDayWindow(LocalDate.parse("2026-08-30"), moscow)
        store.replaceConfirmed("destination-a", listOf(entry("a", window, HistoryBatchStatus.CONFIRMED, "destination-a")))
        assertEquals(1, store.entries("destination-a").size)
        assertTrue(store.entries("destination-b").isEmpty())
        assertEquals(null, parseDriveHistoryEntry(mapOf("batchId" to "missing-window"), "destination-a"))
    }

    @Test
    fun disconnectedRevokedNetworkAndMalformedInventoryRemainUnknown() = runBlocking {
        val store = InMemoryExportHistoryStore()
        val gateway = FakeGoogleDriveGateway(accountId = "synthetic")
        val refresher = DriveHistoryInventoryRefresher(gateway, store, "installation-test", "destination-a")
        gateway.failOnVerifyAccess = GoogleDriveException.AuthorizationException("revoked")
        assertTrue(refresher.refresh() is HistoryRefreshResult.Unknown)
        gateway.failOnVerifyAccess = GoogleDriveException.TimeoutException("offline")
        assertTrue(refresher.refresh() is HistoryRefreshResult.Unknown)
        gateway.failOnVerifyAccess = null
        gateway.files += GoogleDriveFile(
            id = "malformed",
            name = "synthetic.json",
            mimeType = "application/json",
            appProperties = mapOf("installationId" to "installation-test", "batchId" to "missing-window"),
        )
        assertTrue(refresher.refresh() is HistoryRefreshResult.Unknown)
        assertTrue(store.entries("destination-a").isEmpty())
    }

    @Test
    fun manualBackfillUsesStableIdentityAndNeverChangesIncrementalCheckpoint() = runBlocking {
        val state = InMemoryExportStateStore("installation-test")
        val originalCheckpoint = ExportCheckpoint(
            lastWindowEnd = Instant.parse("2026-08-31T00:00:00Z"),
            lastBatchId = "incremental",
            exportedAt = Instant.parse("2026-08-31T00:01:00Z"),
            totalRecordCount = 10,
        )
        state.saveCheckpoint(originalCheckpoint)
        val history = InMemoryExportHistoryStore()
        val reader = RecordingReader(listOf(sampleRecord()))
        val destination = RecordingDestination()
        val backfill = ManualBackfillCoordinator(
            exportStateStore = state,
            historyStore = history,
            recordReader = reader,
            destination = destination,
            destinationKey = "destination-a",
            clock = object : DiagnosticClock {
                override fun now(zoneId: ZoneId) = Instant.parse("2026-08-31T10:00:00Z").atZone(zoneId)
            },
        )
        val dates = listOf(LocalDate.parse("2026-08-28"), LocalDate.parse("2026-08-29"))

        val result = backfill.uploadDays(dates, moscow)

        assertTrue(result is ManualBackfillResult.Success)
        assertEquals(2, reader.windows.size)
        assertEquals(2, destination.uploadedBatches.size)
        assertEquals(originalCheckpoint, state.getLastCheckpoint())
        assertEquals(
            stableBackfillBatchId("destination-a", localDayWindow(dates[0], moscow)),
            destination.uploadedBatches[0].header.batchId,
        )
        assertTrue(history.entries("destination-a").all { it.status == HistoryBatchStatus.CONFIRMED })

        backfill.uploadDays(listOf(dates[0]), moscow)
        assertEquals(3, destination.uploadedBatches.size)
        assertEquals(
            stableBackfillBatchId("destination-a", localDayWindow(dates[0], moscow)),
            destination.uploadedBatches.last().header.batchId,
        )
        assertEquals(3, reader.windows.size)
    }

    @Test
    fun emptyDayReportsNoRecordsAndDoesNotClaimUpload() = runBlocking {
        val history = InMemoryExportHistoryStore()
        val destination = RecordingDestination()
        val coordinator = ManualBackfillCoordinator(
            exportStateStore = InMemoryExportStateStore("installation-test"),
            historyStore = history,
            recordReader = RecordingReader(emptyList()),
            destination = destination,
            destinationKey = "destination-a",
        )
        val result = coordinator.uploadDays(listOf(LocalDate.parse("2026-08-30")), moscow)
        assertTrue(result is ManualBackfillResult.NoRecordsFound)
        assertTrue(destination.uploadedBatches.isEmpty())
        assertTrue(history.entries("destination-a").isEmpty())
    }

    @Test
    fun manualBackfillUsesTrustedSourcePolicy() = runBlocking {
        val client = FakeHealthConnectClient()
        val start = Instant.parse("2026-08-30T08:00:00Z")
        val end = Instant.parse("2026-08-30T09:00:00Z")
        client.setPackageName("com.google.android.apps.fitness")
        client.insertRecords(listOf(StepsRecord(start, null, end, null, 100, Metadata.manualEntry())))
        client.setPackageName("com.xiaomi.wearable")
        client.insertRecords(listOf(StepsRecord(start, null, end, null, 200, Metadata.manualEntry())))
        val destination = RecordingDestination()
        val coordinator = ManualBackfillCoordinator(
            exportStateStore = InMemoryExportStateStore("installation-test"),
            historyStore = InMemoryExportHistoryStore(),
            recordReader = HealthConnectExportReader(client),
            destination = destination,
            destinationKey = "destination-a",
        )

        val result = coordinator.uploadDays(listOf(LocalDate.parse("2026-08-30")), moscow)

        assertTrue(result is ManualBackfillResult.Success)
        val exported = destination.uploadedBatches.single().records.single() as CanonicalStepsRecord
        assertEquals(200L, exported.count)
        assertEquals("com.xiaomi.wearable", exported.metadata.origin)
    }

    @Test
    fun manualBackfillAlsoExportsOnlyLatestProgressiveSleepSnapshot() = runBlocking {
        val start = Instant.parse("2026-09-03T23:00:00Z")
        val snapshots = listOf(
            "2026-09-04T01:38:00Z",
            "2026-09-04T02:22:00Z",
            "2026-09-04T05:06:00Z",
            "2026-09-04T06:54:00Z",
        ).mapIndexed { index, end ->
            val endTime = Instant.parse(end)
            CanonicalSleepSessionRecord(
                startTime = start,
                startZoneOffset = ZoneOffset.UTC,
                endTime = endTime,
                endZoneOffset = ZoneOffset.UTC,
                metadata = RecordMetadata(
                    recordId = "xiaomi-revision-$index",
                    origin = "com.xiaomi.wearable",
                ),
                stages = listOf(SleepStage(start, endTime, 4)),
            )
        }
        val destination = RecordingDestination()
        val coordinator = ManualBackfillCoordinator(
            exportStateStore = InMemoryExportStateStore("installation-test"),
            historyStore = InMemoryExportHistoryStore(),
            recordReader = RecordingReader(snapshots),
            destination = destination,
            destinationKey = "destination-a",
        )

        val result = coordinator.uploadDays(listOf(LocalDate.parse("2026-09-04")), moscow)

        assertTrue(result is ManualBackfillResult.Success)
        val batch = destination.uploadedBatches.single()
        assertEquals(1, batch.header.recordCount)
        assertEquals(Instant.parse("2026-09-04T06:54:00Z"), batch.records.single().endTime)
    }

    @Test
    fun retryKeepsStableBatchIdentityWithoutDuplicateLogicalUpload() = runBlocking {
        val history = InMemoryExportHistoryStore()
        val destination = RecordingDestination().apply { failNext = true }
        val reader = RecordingReader(listOf(sampleRecord()))
        val coordinator = ManualBackfillCoordinator(
            exportStateStore = InMemoryExportStateStore("installation-test"),
            historyStore = history,
            recordReader = reader,
            destination = destination,
            destinationKey = "destination-a",
        )
        val date = LocalDate.parse("2026-08-30")
        assertTrue(coordinator.uploadDays(listOf(date), moscow) is ManualBackfillResult.Retrying)
        val pendingId = history.entries("destination-a").single().batchId
        assertTrue(coordinator.uploadDays(listOf(date), moscow) is ManualBackfillResult.Success)
        assertEquals(pendingId, destination.uploadedBatches.last().header.batchId)
        assertEquals(1, history.entries("destination-a").size)
        assertEquals(1, reader.windows.size)
    }

    @Test
    fun presenterRequiresSelectionAndConfirmationBeforeUpload() {
        val presenter = ExportHistoryPresenter(moscow)
        presenter.show(
            dates = listOf(LocalDate.parse("2026-08-30")),
            entries = emptyList(),
            inventoryKnown = true,
        )
        assertFalse(presenter.state.canUpload)
        presenter.toggle(LocalDate.parse("2026-08-30"))
        assertTrue(presenter.state.canUpload)
        val confirmation = presenter.requestConfirmation()
        assertEquals("2026-08-30", confirmation.rangeLabel)
        assertFalse(presenter.state.uploadStarted)
        presenter.confirmUpload()
        assertTrue(presenter.state.uploadStarted)
    }

    @Test
    fun presenterAllowsPreviouslyUploadedAndUnknownDaysToBeSelectedAgain() {
        val uploadedDate = LocalDate.parse("2026-08-30")
        val unknownDate = LocalDate.parse("2026-08-29")
        val presenter = ExportHistoryPresenter(moscow)
        presenter.show(
            dates = listOf(uploadedDate),
            entries = listOf(entry("uploaded", localDayWindow(uploadedDate, moscow), HistoryBatchStatus.CONFIRMED)),
            inventoryKnown = true,
        )

        presenter.toggle(uploadedDate)
        assertTrue(presenter.state.rows.single().selected)

        presenter.show(listOf(unknownDate), emptyList(), inventoryKnown = false)
        presenter.toggle(unknownDate)
        assertTrue(presenter.state.rows.single().selected)
    }

    @Test
    fun loadMoreAddsExactlyTenEarlierCalendarDates() {
        val presenter = ExportHistoryPresenter(moscow)
        presenter.show(
            dates = (0L until 14L).map { LocalDate.parse("2026-08-30").minusDays(it) },
            entries = emptyList(),
            inventoryKnown = true,
        )

        presenter.loadMore(entries = emptyList())

        assertEquals(24, presenter.state.rows.size)
        assertEquals(LocalDate.parse("2026-08-07"), presenter.state.rows.last().date)
        assertEquals(24, presenter.state.rows.map { it.date }.distinct().size)
    }

    private fun entry(
        id: String,
        window: TimeWindow,
        status: HistoryBatchStatus,
        destinationKey: String = "destination-a",
    ) = ExportHistoryEntry(id, window, status, destinationKey, Instant.parse("2026-08-31T00:00:00Z"))

    private fun sampleRecord() = CanonicalStepsRecord(
        startTime = Instant.parse("2026-08-30T08:00:00Z"),
        startZoneOffset = null,
        endTime = Instant.parse("2026-08-30T09:00:00Z"),
        endZoneOffset = null,
        metadata = RecordMetadata(recordId = "synthetic", origin = "synthetic.test"),
        count = 123,
    )

    private class RecordingReader(private val records: List<CanonicalRecord>) : HealthExportRecordReader {
        val windows = mutableListOf<TimeWindow>()
        override suspend fun readRecords(timeWindow: TimeWindow): List<CanonicalRecord> {
            windows += timeWindow
            return records
        }
    }

    private class RecordingDestination : ExportDestination {
        override val destinationName = "test"
        val uploadedBatches = mutableListOf<ExportBatch>()
        var failNext = false
        override suspend fun verifyConfiguration(): DestinationStatus = DestinationStatus.Ready
        override suspend fun upload(batch: ExportBatch): UploadResult {
            uploadedBatches += batch
            if (failNext) {
                failNext = false
                return UploadResult.Failure("indeterminate", isRetryable = true)
            }
            return UploadResult.Success(batch.header.batchId, batch.header.batchId)
        }
    }
}
