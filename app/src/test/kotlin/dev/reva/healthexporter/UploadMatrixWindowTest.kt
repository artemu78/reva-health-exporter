package dev.reva.healthexporter

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class UploadMatrixWindowTest {
    @Test fun windowHasFiveMondayFirstWeeksAcrossYearBoundary() {
        val dates = uploadMatrixDates(LocalDate.parse("2026-01-01"), 0)
        assertEquals(35, dates.size)
        assertEquals(LocalDate.parse("2025-12-01"), dates.first())
        assertEquals(LocalDate.parse("2026-01-04"), dates.last())
        assertEquals(35, dates.distinct().size)
    }
    @Test fun previousWindowIsAdjacentWithoutOverlap() {
        val today = LocalDate.parse("2026-09-12")
        val current = uploadMatrixDates(today, 0)
        val previous = uploadMatrixDates(today, 1)
        assertEquals(current.first().minusDays(1), previous.last())
        assertTrue(current.intersect(previous.toSet()).isEmpty())
    }
}
