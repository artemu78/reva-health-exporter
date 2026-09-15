package dev.reva.healthexporter

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class EmaConfigStoreTest {
    @Test
    fun defaultsAndCustomCategoriesPersistAcrossInstances() {
        val preferences = FakeSharedPreferences()
        val initial = SharedPreferencesEmaConfigStore(preferences).load()
        assertEquals(LocalTime.of(9, 0), initial.activeStart)
        assertEquals(LocalTime.of(22, 0), initial.activeEnd)
        assertEquals(5, initial.checkInsPerDay)
        assertEquals(true, initial.notificationsEnabled)

        val custom = initial.copy(
            activeStart = LocalTime.of(8, 30),
            activeEnd = LocalTime.of(21, 15),
            checkInsPerDay = 4,
            notificationsEnabled = false,
            activityCategories = listOf(
                EmaActivityCategory("deep_work", "Deep work"),
                EmaActivityCategory("other", "Other"),
            ),
        )
        SharedPreferencesEmaConfigStore(preferences).save(custom)

        assertEquals(custom, SharedPreferencesEmaConfigStore(preferences).load())
    }
}
