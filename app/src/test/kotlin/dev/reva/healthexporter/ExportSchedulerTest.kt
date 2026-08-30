package dev.reva.healthexporter

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import androidx.work.WorkRequest
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportSchedulerTest {

    @Test
    fun periodicWorkRequestHasExpectedConstraintsAndBackoff() {
        val request = ExportScheduler.buildPeriodicWorkRequest(interval = Duration.ofHours(2))

        assertNotNull(request.id)
        assertTrue(request.tags.contains(ExportScheduler.TAG_EXPORT_WORK))

        val constraints = request.workSpec.constraints
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresBatteryNotLow())

        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertTrue(request.workSpec.backoffDelayDuration >= WorkRequest.MIN_BACKOFF_MILLIS)
    }

    @Test
    fun oneTimeWorkRequestHasExpectedConstraintsAndBackoff() {
        val request = ExportScheduler.buildOneTimeWorkRequest()

        assertNotNull(request.id)
        assertTrue(request.tags.contains(ExportScheduler.TAG_EXPORT_WORK))

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
