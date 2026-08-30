package dev.reva.healthexporter

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkRequest
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportSchedulerTest {

    @Test
    fun periodicWorkRequestHasExpectedConstraintsAndBackoff() {
        val request = ExportScheduler.buildPeriodicWorkRequest(
            interval = Duration.ofHours(2),
            accountId = "test-account",
        )

        assertNotNull(request.id)
        assertTrue(request.tags.contains(ExportScheduler.TAG_EXPORT_WORK))
        assertEquals("test-account", request.workSpec.input.getString(ExportScheduler.KEY_ACCOUNT_ID))
        assertEquals(Duration.ofHours(2).toMillis(), request.workSpec.intervalDuration)

        val constraints = request.workSpec.constraints
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresBatteryNotLow())

        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertTrue(request.workSpec.backoffDelayDuration >= WorkRequest.MIN_BACKOFF_MILLIS)
    }

    @Test
    fun periodicWorkRequestClampsIntervalAndFlexDurations() {
        // Below minimum interval (5 min < 15 min), and flex exceeds interval (30 min > 15 min)
        val request = ExportScheduler.buildPeriodicWorkRequest(
            interval = Duration.ofMinutes(5),
            flexInterval = Duration.ofMinutes(30),
        )

        assertEquals(PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS, request.workSpec.intervalDuration)
        assertEquals(PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS, request.workSpec.flexDuration)
    }

    @Test
    fun oneTimeWorkRequestHasExpectedConstraintsAndBackoff() {
        val request = ExportScheduler.buildOneTimeWorkRequest(accountId = "acc-456")

        assertNotNull(request.id)
        assertTrue(request.tags.contains(ExportScheduler.TAG_EXPORT_WORK))
        assertEquals("acc-456", request.workSpec.input.getString(ExportScheduler.KEY_ACCOUNT_ID))

        val constraints = request.workSpec.constraints
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresBatteryNotLow())

        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertTrue(request.workSpec.backoffDelayDuration >= WorkRequest.MIN_BACKOFF_MILLIS)
    }

    @Test
    fun uniqueWorkNamesAreConsistent() {
        assertEquals("reva_periodic_health_export", ExportScheduler.PERIODIC_WORK_NAME)
        assertEquals("reva_immediate_health_export", ExportScheduler.ONE_TIME_WORK_NAME)
        assertEquals("reva_export_work", ExportScheduler.TAG_EXPORT_WORK)
    }
}
