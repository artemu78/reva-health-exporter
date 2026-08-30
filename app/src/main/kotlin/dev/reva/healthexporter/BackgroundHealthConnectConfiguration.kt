package dev.reva.healthexporter

import androidx.health.connect.client.permission.HealthPermission

object BackgroundHealthConnectConfiguration {
    const val BACKGROUND_READ_PERMISSION: String =
        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    val CONFIRMED_CORE_METRICS: Set<HealthMetric> = setOf(
        HealthMetric.STEPS,
        HealthMetric.HEART_RATE,
        HealthMetric.DISTANCE,
        HealthMetric.TOTAL_CALORIES_BURNED,
        HealthMetric.SLEEP,
    )
}
