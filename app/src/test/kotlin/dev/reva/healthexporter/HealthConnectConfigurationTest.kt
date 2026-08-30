package dev.reva.healthexporter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthConnectConfigurationTest {
    @Test
    fun `runtime requests exactly the selected read permissions`() {
        val permissions = HealthConnectConfiguration.permissionByMetric.values.toSet()

        assertEquals(HealthMetric.entries.size, permissions.size)
        assertEquals(
            setOf(
                "android.permission.health.READ_STEPS",
                "android.permission.health.READ_HEART_RATE",
                "android.permission.health.READ_RESTING_HEART_RATE",
                "android.permission.health.READ_SLEEP",
                "android.permission.health.READ_DISTANCE",
                "android.permission.health.READ_TOTAL_CALORIES_BURNED",
                "android.permission.health.READ_EXERCISE",
                "android.permission.health.READ_OXYGEN_SATURATION",
            ),
            permissions,
        )
        assertTrue(permissions.none { "WRITE" in it })
    }

    @Test
    fun `permission action requests only missing metrics`() {
        val state = summarizePermissions(
            required = setOf(HealthMetric.STEPS, HealthMetric.HEART_RATE),
            granted = setOf(HealthMetric.STEPS),
        )

        assertEquals(
            setOf("android.permission.health.READ_HEART_RATE"),
            permissionsToRequest(state),
        )
    }
}
