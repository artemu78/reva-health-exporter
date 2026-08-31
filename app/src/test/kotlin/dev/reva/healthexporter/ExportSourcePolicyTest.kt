package dev.reva.healthexporter

import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportSourcePolicyTest {
    @Test
    fun definesTheTrustedOriginForEveryExportedRecordType() {
        assertEquals("com.xiaomi.wearable", ExportSourcePolicy.allowedPackageName(StepsRecord::class))
        assertEquals("com.xiaomi.wearable", ExportSourcePolicy.allowedPackageName(HeartRateRecord::class))
        assertEquals("com.xiaomi.wearable", ExportSourcePolicy.allowedPackageName(SleepSessionRecord::class))
        assertEquals("com.xiaomi.wearable", ExportSourcePolicy.allowedPackageName(OxygenSaturationRecord::class))
        assertEquals("com.google.android.apps.fitness", ExportSourcePolicy.allowedPackageName(DistanceRecord::class))
        assertEquals(
            "com.google.android.apps.fitness",
            ExportSourcePolicy.allowedPackageName(TotalCaloriesBurnedRecord::class),
        )
    }
}
