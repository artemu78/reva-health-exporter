package dev.reva.healthexporter

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord

object HealthConnectConfiguration {
    const val PROVIDER_PACKAGE_NAME = "com.google.android.apps.healthdata"

    val permissionByMetric: Map<HealthMetric, String> = mapOf(
        HealthMetric.STEPS to HealthPermission.getReadPermission(StepsRecord::class),
        HealthMetric.HEART_RATE to HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthMetric.RESTING_HEART_RATE to HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthMetric.SLEEP to HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthMetric.DISTANCE to HealthPermission.getReadPermission(DistanceRecord::class),
        HealthMetric.TOTAL_CALORIES_BURNED to
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthMetric.EXERCISE_SESSIONS to
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthMetric.OXYGEN_SATURATION to
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
    )

    val selectedMetrics: Set<HealthMetric> = permissionByMetric.keys
    val readPermissions: Set<String> = permissionByMetric.values.toSet()
}

fun permissionsToRequest(summary: PermissionSummary): Set<String> =
    summary.missing
        .mapNotNull(HealthConnectConfiguration.permissionByMetric::get)
        .toSet()
