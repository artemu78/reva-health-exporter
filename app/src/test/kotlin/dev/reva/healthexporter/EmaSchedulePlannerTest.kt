package dev.reva.healthexporter

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmaSchedulePlannerTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val date = LocalDate.of(2026, 9, 15)
    private val config = EmaConfig(
        activeStart = LocalTime.of(9, 0),
        activeEnd = LocalTime.of(22, 0),
        checkInsPerDay = 5,
        notificationsEnabled = true,
    )

    @Test
    fun plansFiveSeparatedPromptsInsideActiveHours() {
        val prompts = EmaSchedulePlanner { 0.5 }.plan(date, zone, config)

        assertEquals(5, prompts.size)
        assertTrue(prompts.all { it.atZone(zone).toLocalDate() == date })
        assertTrue(prompts.all {
            val time = it.atZone(zone).toLocalTime()
            !time.isBefore(config.activeStart) && time.isBefore(config.activeEnd)
        })
        prompts.zipWithNext().forEach { (earlier, later) ->
            assertTrue(ChronoUnit.MINUTES.between(earlier, later) >= 60)
        }
    }

    @Test
    fun differentRandomSamplesProduceDifferentPromptTimes() {
        val early = EmaSchedulePlanner { 0.1 }.plan(date, zone, config)
        val late = EmaSchedulePlanner { 0.9 }.plan(date, zone, config)

        assertNotEquals(early, late)
    }

    @Test
    fun disabledNotificationsProduceNoPrompts() {
        val prompts = EmaSchedulePlanner { 0.5 }.plan(
            date,
            zone,
            config.copy(notificationsEnabled = false),
        )

        assertTrue(prompts.isEmpty())
    }
}
