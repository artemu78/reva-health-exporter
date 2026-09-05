package dev.reva.healthexporter

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DailySnapshotTest {
    private val moscow = ZoneId.of("Europe/Moscow")

    @Test fun identityIsStableAndIndependentOfChangingRefreshInstant() {
        val key = dailySnapshotKey("drive", "account", moscow, LocalDate.parse("2026-09-05"))
        assertEquals(key.identity, dailySnapshotKey("drive", "account", moscow, key.date).identity)
        assertEquals("2026-09-05.json", key.filename)
        assertNotEquals(key.identity, dailySnapshotKey("drive", "account", moscow, key.date.plusDays(1)).identity)
    }

    @Test fun dstWindowsUseTheActualLocalDayLength() {
        val zone = ZoneId.of("Europe/Berlin")
        assertEquals(23L, localDayWindow(LocalDate.parse("2026-03-29"), zone).duration.toHours())
        assertEquals(25L, localDayWindow(LocalDate.parse("2026-10-25"), zone).duration.toHours())
    }

    @Test fun currentDateAndPreviousDateAreSelectedForRefresh() {
        val dates = dailySnapshotRefreshDates(
            LocalDate.parse("2026-09-05").atStartOfDay(moscow).toInstant(),
            moscow,
        )
        assertEquals(listOf(LocalDate.parse("2026-09-05"), LocalDate.parse("2026-09-04")), dates)
    }
}
