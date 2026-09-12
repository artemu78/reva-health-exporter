package dev.reva.healthexporter

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

internal fun uploadMatrixDates(today: LocalDate, windowsBack: Int): List<LocalDate> {
    require(windowsBack >= 0)
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        .minusWeeks(4).minusDays(windowsBack.toLong() * 35)
    return (0L..34L).map(monday::plusDays)
}
