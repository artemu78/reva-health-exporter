package dev.reva.healthexporter

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

data class EmaActivityCategory(
    val id: String,
    val label: String,
)

data class EmaConfig(
    val activeStart: LocalTime = LocalTime.of(9, 0),
    val activeEnd: LocalTime = LocalTime.of(22, 0),
    val checkInsPerDay: Int = 5,
    val notificationsEnabled: Boolean = true,
    val activityCategories: List<EmaActivityCategory> = DEFAULT_EMA_ACTIVITIES,
) {
    init {
        require(activeStart != activeEnd) { "Active hours must not span a full day" }
        require(checkInsPerDay in 1..10) { "Check-ins per day must be between 1 and 10" }
        require(activityCategories.isNotEmpty()) { "At least one activity category is required" }
        require(activityCategories.map { it.id }.distinct().size == activityCategories.size) {
            "Activity category IDs must be unique"
        }
    }
}

val DEFAULT_EMA_ACTIVITIES = listOf(
    EmaActivityCategory("work_coding", "Work / coding"),
    EmaActivityCategory("learning_reading", "Learning / reading"),
    EmaActivityCategory("communication_social", "Communication / social"),
    EmaActivityCategory("entertainment", "Entertainment"),
    EmaActivityCategory("social_media_browsing", "Social media / browsing"),
    EmaActivityCategory("exercise_walking", "Exercise / walking"),
    EmaActivityCategory("eating", "Eating"),
    EmaActivityCategory("resting", "Resting"),
    EmaActivityCategory("household_errands", "Household / errands"),
    EmaActivityCategory("traveling", "Traveling"),
    EmaActivityCategory("other", "Other"),
)

fun interface EmaRandomSource {
    fun nextDouble(): Double
}

class EmaSchedulePlanner(
    private val random: EmaRandomSource = EmaRandomSource { Random.nextDouble() },
) {
    fun plan(date: LocalDate, zoneId: ZoneId, config: EmaConfig): List<Instant> {
        if (!config.notificationsEnabled) return emptyList()

        val start = date.atTime(config.activeStart).atZone(zoneId)
        val endDate = if (config.activeEnd > config.activeStart) date else date.plusDays(1)
        val end = endDate.atTime(config.activeEnd).atZone(zoneId)
        val activeMinutes = Duration.between(start, end).toMinutes()
        val count = min(config.checkInsPerDay, max(1, (activeMinutes / MIN_SPACING_MINUTES).toInt()))
        val spacing = activeMinutes.toDouble() / (count + 1)
        val jitterRadius = max(0.0, min(spacing * 0.25, (spacing - MIN_SPACING_MINUTES) / 2.0))

        return (1..count).map { index ->
            val sample = random.nextDouble().coerceIn(0.0, 0.999999)
            val jitter = (sample * 2.0 - 1.0) * jitterRadius
            start.plusMinutes((spacing * index + jitter).toLong()).toInstant()
        }
    }

    companion object {
        const val MIN_SPACING_MINUTES = 60L
    }
}
