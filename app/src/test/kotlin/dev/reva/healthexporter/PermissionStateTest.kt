package dev.reva.healthexporter

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionStateTest {
    private val required = setOf(HealthMetric.STEPS, HealthMetric.HEART_RATE)

    @Test
    fun `no permissions are reported as missing without a denial`() {
        val state = summarizePermissions(required, granted = emptySet())

        assertEquals(emptySet<HealthMetric>(), state.granted)
        assertEquals(required, state.missing)
        assertEquals(PermissionNotice.NONE, state.notice)
    }

    @Test
    fun `partial permissions keep granted and missing metrics distinct`() {
        val state = summarizePermissions(required, granted = setOf(HealthMetric.STEPS))

        assertEquals(setOf(HealthMetric.STEPS), state.granted)
        assertEquals(setOf(HealthMetric.HEART_RATE), state.missing)
        assertEquals(PermissionNotice.NONE, state.notice)
    }

    @Test
    fun `all permissions produce no missing metrics`() {
        val state = summarizePermissions(required, granted = required)

        assertEquals(required, state.granted)
        assertEquals(emptySet<HealthMetric>(), state.missing)
        assertEquals(PermissionNotice.NONE, state.notice)
    }

    @Test
    fun `denial is visible while the permission summary remains usable`() {
        val state = summarizePermissions(required, granted = emptySet(), requestDenied = true)

        assertEquals(required, state.missing)
        assertEquals(PermissionNotice.DENIED, state.notice)
    }

    @Test
    fun `loss after full access is classified as revocation`() {
        val state = summarizePermissions(
            required = required,
            granted = setOf(HealthMetric.STEPS),
            previouslyGranted = required,
        )

        assertEquals(setOf(HealthMetric.HEART_RATE), state.missing)
        assertEquals(PermissionNotice.REVOKED, state.notice)
    }
}
