package dev.reva.healthexporter

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DayDetailsTest {
    @Test
    fun selectedDayGroupsRecordsAndTotalsStepsWithoutDroppingOtherSources() = runBlocking {
        val start = Instant.parse("2026-09-20T10:00:00Z")
        val reader = DayDetailsReader { _, _ ->
            listOf(100L, 250L).mapIndexed { index, count ->
                CanonicalStepsRecord(start, null, start.plusSeconds(60), null,
                    RecordMetadata("id-$index", if (index == 0) "com.xiaomi.wearable" else "other.app"), count)
            }
        }
        val day = DayDetailsLoader(reader, InMemoryEmaEventStore()).load(LocalDate.parse("2026-09-20"), ZoneOffset.UTC)
        val steps = day.groups.single { it.type == DetailType.STEPS }
        assertEquals(9, day.groups.size)
        assertEquals(2, steps.records.size)
        assertEquals("2 records · 350 steps (raw sum)", steps.summary)
        assertEquals(1, steps.records.count { it.exportEligible })
    }
    @Test
    fun dayBoundariesFailuresAndEmaStatusesRemainDistinct() = runBlocking {
        val date = LocalDate.parse("2026-03-29")
        val zone = java.time.ZoneId.of("Europe/Berlin")
        val window = localDayWindow(date, zone)
        assertEquals(23, java.time.Duration.between(window.startInclusive, window.endExclusive).toHours())
        val store = InMemoryEmaEventStore()
        EmaResponseStatus.entries.forEach { status ->
            store.save(EmaEvent.pending(status.name, window.startInclusive, zone).copy(status = status))
        }
        store.save(EmaEvent.pending("yesterday", window.startInclusive.minusSeconds(1), zone))
        val reader = DayDetailsReader { type, requested ->
            assertEquals(window, requested)
            when (type) {
                DetailType.HEART_RATE -> throw SecurityException("private message")
                DetailType.SLEEP -> throw java.io.IOException("private message")
                DetailType.STEPS -> listOf(window.startInclusive.minusSeconds(1), window.startInclusive, window.endExclusive).mapIndexed { i, time ->
                    CanonicalStepsRecord(time, null, time.plusSeconds(1), null, RecordMetadata("$i", "other.app"), 10)
                }
                else -> emptyList()
            }
        }
        val day = DayDetailsLoader(reader, store).load(date, zone)
        assertEquals(1, day.groups.single { it.type == DetailType.STEPS }.records.size)
        assertEquals("Unavailable", day.groups.single { it.type == DetailType.HEART_RATE }.summary)
        assertEquals("Read failed", day.groups.single { it.type == DetailType.SLEEP }.summary)
        assertEquals("0 records", day.groups.single { it.type == DetailType.DISTANCE }.summary)
        val ema = day.groups.single { it.type == DetailType.EMA }
        assertEquals("4 records · 1 answered", ema.summary)
        assertEquals(1, ema.records.count { it.exportEligible })
        org.junit.Assert.assertFalse(day.toString().contains("private message"))
    }

    @Test
    fun cancellationStopsTheReadInsteadOfBecomingAnEmptyGroup() {
        org.junit.Assert.assertThrows(java.util.concurrent.CancellationException::class.java) {
            runBlocking {
                DayDetailsLoader(DayDetailsReader { _, _ -> throw java.util.concurrent.CancellationException() },
                    InMemoryEmaEventStore()).load(LocalDate.parse("2026-09-20"), ZoneOffset.UTC)
            }
        }
    }

    @Test
    fun partialEmaDetailsShowOnlyPresentMeasurements() = runBlocking {
        val date = LocalDate.parse("2026-09-26")
        val store = InMemoryEmaEventStore().apply {
            save(EmaEvent.pending("partial", Instant.parse("2026-09-26T09:00:00Z"), ZoneOffset.UTC).copy(
                schemaVersion = 2,
                answeredAt = Instant.parse("2026-09-26T09:01:00Z"),
                answers = EmaAnswers(focus = 5),
                status = EmaResponseStatus.ANSWERED,
            ))
        }
        val text = DayDetailsLoader(DayDetailsReader { _, _ -> emptyList() }, store)
            .load(date, ZoneOffset.UTC).groups.single { it.type == DetailType.EMA }.records.single().text
        org.junit.Assert.assertTrue(text.contains("Focus 5/5"))
        org.junit.Assert.assertFalse(text.contains("Mood"))
        org.junit.Assert.assertFalse(text.contains("null/5"))
    }

    @Test
    fun sleepSummarySeparatesAsleepStagesFromSessionDuration() = runBlocking {
        val start = Instant.parse("2026-09-20T00:00:00Z")
        val sleep = CanonicalSleepSessionRecord(start, null, start.plusSeconds(8 * 3600), null,
            RecordMetadata("sleep", "com.xiaomi.wearable"), stages = listOf(
                SleepStage(start, start.plusSeconds(3600), 1),
                SleepStage(start.plusSeconds(3600), start.plusSeconds(8 * 3600), 4)))
        val day = DayDetailsLoader(DayDetailsReader { type, _ -> if (type == DetailType.SLEEP) listOf(sleep) else emptyList() },
            InMemoryEmaEventStore()).load(LocalDate.parse("2026-09-20"), ZoneOffset.UTC)
        assertEquals("1 records · 7h 0m 0s asleep in recorded stages · 8h 0m 0s session duration (raw sums)",
            day.groups.single { it.type == DetailType.SLEEP }.summary)
    }
    @Test
    fun allMetricsExposeValuesNestedRecordsAndMetadata() = runBlocking {
        val start = Instant.parse("2026-09-20T10:00:00Z")
        val end = start.plusSeconds(3600)
        val metadata = RecordMetadata("synthetic-id", "com.google.android.apps.fitness", "client", 2, 1,
            DeviceMetadata("Synthetic", "Test", 1), start)
        val records = mapOf(
            DetailType.DISTANCE to CanonicalDistanceRecord(start, null, end, null, metadata, 1200.5),
            DetailType.CALORIES to CanonicalTotalCaloriesBurnedRecord(start, null, end, null, metadata, 150.5),
            DetailType.OXYGEN to CanonicalOxygenSaturationRecord(start, null, start, null, metadata, 98.0),
            DetailType.RESTING_HEART_RATE to CanonicalRestingHeartRateRecord(start, null, start, null, metadata, 55),
            DetailType.HEART_RATE to CanonicalHeartRateRecord(start, null, end, null, metadata, listOf(HeartRateSample(start, 70), HeartRateSample(end, 90))),
            DetailType.WORKOUTS to CanonicalExerciseSessionRecord(start, null, end, null, metadata, 56, "Walk", "Synthetic workout",
                listOf(ExerciseSegmentModel(start, end, 1, 10)), listOf(ExerciseLapModel(start, end, 1000.0))),
        )
        val store = InMemoryEmaEventStore()
        store.save(EmaEvent.pending("answered", start, ZoneOffset.UTC).copy(status = EmaResponseStatus.ANSWERED,
            answeredAt = end, answers = EmaAnswers(1, 2, 3, 4, mapOf("extra" to 5)), activity = "work", activityLabel = "Working", note = "Synthetic note"))
        val day = DayDetailsLoader(DayDetailsReader { type, _ -> listOfNotNull(records[type]) }, store)
            .load(LocalDate.parse("2026-09-20"), ZoneOffset.UTC)
        val groups = day.groups.associateBy { it.type }
        assertEquals("1 records · 1200.5 m (raw sum)", groups.getValue(DetailType.DISTANCE).summary)
        assertEquals("1 records · 150.5 kcal (raw sum)", groups.getValue(DetailType.CALORIES).summary)
        assertEquals("1 records · 98.0% average", groups.getValue(DetailType.OXYGEN).summary)
        assertEquals("1 records · 55.0 bpm average", groups.getValue(DetailType.RESTING_HEART_RATE).summary)
        assertEquals("1 records · 2 samples", groups.getValue(DetailType.HEART_RATE).summary)
        assertEquals("1 records · 1h 0m 0s workouts (raw duration)", groups.getValue(DetailType.WORKOUTS).summary)
        val workout = groups.getValue(DetailType.WORKOUTS).records.single().text
        listOf("Running (56)", "Walk", "Synthetic workout", "Segment 1", "10 repetitions", "1000.0 m", "synthetic-id", "Client ID: client", "Synthetic Test").forEach {
            org.junit.Assert.assertTrue("Missing $it", workout.contains(it))
        }
        org.junit.Assert.assertTrue(groups.getValue(DetailType.HEART_RATE).records.single().text.contains("90 bpm"))
        val ema = groups.getValue(DetailType.EMA).records.single().text
        listOf("Mood 1/5", "Energy 2/5", "Focus 3/5", "Stress 4/5", "extra: 5", "Working", "Synthetic note", "Answered:").forEach {
            org.junit.Assert.assertTrue("Missing $it", ema.contains(it))
        }
    }
}
