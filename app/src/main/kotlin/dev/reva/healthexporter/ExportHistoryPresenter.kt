package dev.reva.healthexporter

import java.time.LocalDate
import java.time.ZoneId

data class ExportHistoryRow(val date: LocalDate, val coverage: DayCoverage, val selected: Boolean)

data class ExportHistoryScreenState(
    val zoneId: ZoneId,
    val rows: List<ExportHistoryRow> = emptyList(),
    val inventoryKnown: Boolean = false,
    val canUpload: Boolean = false,
    val uploadStarted: Boolean = false,
)

data class BackfillConfirmation(val dates: List<LocalDate>, val rangeLabel: String)

class ExportHistoryPresenter(private val zoneId: ZoneId = ZoneId.systemDefault()) {
    var state = ExportHistoryScreenState(zoneId)
        private set

    fun show(dates: List<LocalDate>, entries: List<ExportHistoryEntry>, inventoryKnown: Boolean) {
        state = state.copy(
            rows = dates.map { date ->
                ExportHistoryRow(
                    date,
                    classifyDayCoverage(localDayWindow(date, zoneId), entries, inventoryKnown),
                    selected = false,
                )
            },
            inventoryKnown = inventoryKnown,
            canUpload = false,
            uploadStarted = false,
        )
    }

    fun toggle(date: LocalDate) {
        val rows = state.rows.map { row ->
            if (row.date == date) row.copy(selected = !row.selected) else row
        }
        state = state.copy(rows = rows, canUpload = rows.any { it.selected })
    }

    fun loadMore(entries: List<ExportHistoryEntry>, count: Int = 10) {
        require(count > 0)
        val oldestDate = state.rows.minOfOrNull { it.date } ?: LocalDate.now(zoneId).plusDays(1)
        val earlierRows = (1L..count.toLong()).map { offset ->
            val date = oldestDate.minusDays(offset)
            ExportHistoryRow(
                date,
                classifyDayCoverage(localDayWindow(date, zoneId), entries, state.inventoryKnown),
                selected = false,
            )
        }
        state = state.copy(rows = state.rows + earlierRows)
    }

    fun requestConfirmation(): BackfillConfirmation {
        val dates = state.rows.filter { it.selected }.map { it.date }.sorted()
        require(dates.isNotEmpty())
        val label = if (dates.size == 1) dates.single().toString() else "${dates.first()} – ${dates.last()}"
        return BackfillConfirmation(dates, label)
    }

    fun confirmUpload(): List<LocalDate> {
        val dates = requestConfirmation().dates
        state = state.copy(
            rows = state.rows.map { row ->
                if (row.date in dates) row.copy(coverage = DayCoverage.PENDING_RETRYING) else row
            },
            uploadStarted = true,
            canUpload = false,
        )
        return dates
    }

    fun markDateUploaded(date: LocalDate) {
        state = state.copy(rows = state.rows.map { row ->
            if (row.date == date) row.copy(coverage = DayCoverage.UPLOADED, selected = false) else row
        })
    }

    fun finishUpload() {
        state = state.copy(uploadStarted = false, canUpload = state.rows.any { it.selected })
    }
}
