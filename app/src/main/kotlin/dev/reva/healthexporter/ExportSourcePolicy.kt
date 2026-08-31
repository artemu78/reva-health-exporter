package dev.reva.healthexporter

import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import kotlin.reflect.KClass

object ExportSourcePolicy {
    private const val XIAOMI_WEARABLE_PACKAGE = "com.xiaomi.wearable"
    private const val GOOGLE_FIT_PACKAGE = "com.google.android.apps.fitness"

    private val allowedPackagesByRecordType: Map<KClass<out Record>, String> = linkedMapOf(
        StepsRecord::class to XIAOMI_WEARABLE_PACKAGE,
        HeartRateRecord::class to XIAOMI_WEARABLE_PACKAGE,
        SleepSessionRecord::class to XIAOMI_WEARABLE_PACKAGE,
        OxygenSaturationRecord::class to XIAOMI_WEARABLE_PACKAGE,
        DistanceRecord::class to GOOGLE_FIT_PACKAGE,
        TotalCaloriesBurnedRecord::class to GOOGLE_FIT_PACKAGE,
    )

    val recordTypes: List<KClass<out Record>> = allowedPackagesByRecordType.keys.toList()

    fun allowedPackageName(recordType: KClass<out Record>): String =
        requireNotNull(allowedPackagesByRecordType[recordType]) {
            "No export source policy for ${recordType.qualifiedName}"
        }
}
