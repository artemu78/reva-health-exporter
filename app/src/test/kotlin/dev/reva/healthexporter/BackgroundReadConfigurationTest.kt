package dev.reva.healthexporter

import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundReadConfigurationTest {
    @Test
    fun featureStatusClassifiedCorrectly() {
        assertEquals(
            BackgroundReadSupport.AVAILABLE,
            classifyBackgroundReadSupport(HealthConnectFeatures.FEATURE_STATUS_AVAILABLE),
        )
        assertEquals(
            BackgroundReadSupport.UNSUPPORTED,
            classifyBackgroundReadSupport(HealthConnectFeatures.FEATURE_STATUS_UNAVAILABLE),
        )
        assertEquals(
            BackgroundReadSupport.UNSUPPORTED,
            classifyBackgroundReadSupport(0),
        )
    }

    @Test
    fun permissionRequestedOnlyWhenFeatureIsAvailableAndNotGranted() {
        val availableSummary = BackgroundReadSummary(
            support = BackgroundReadSupport.AVAILABLE,
            hasBackgroundPermission = false,
        )
        assertEquals(
            setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND),
            backgroundPermissionsToRequest(availableSummary),
        )

        val alreadyGrantedSummary = BackgroundReadSummary(
            support = BackgroundReadSupport.AVAILABLE,
            hasBackgroundPermission = true,
        )
        assertEquals(
            emptySet<String>(),
            backgroundPermissionsToRequest(alreadyGrantedSummary),
        )

        val unsupportedSummary = BackgroundReadSummary(
            support = BackgroundReadSupport.UNSUPPORTED,
            hasBackgroundPermission = false,
        )
        assertEquals(
            emptySet<String>(),
            backgroundPermissionsToRequest(unsupportedSummary),
        )
    }

    @Test
    fun backgroundReadSummaryTracksPermissionStates() {
        val granted = setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)

        val availableGranted = summarizeBackgroundRead(
            support = BackgroundReadSupport.AVAILABLE,
            grantedPermissions = granted,
        )
        assertTrue(availableGranted.hasBackgroundPermission)
        assertEquals(BackgroundPermissionNotice.NONE, availableGranted.notice)

        val availableDenied = summarizeBackgroundRead(
            support = BackgroundReadSupport.AVAILABLE,
            grantedPermissions = emptySet(),
            requestDenied = true,
        )
        assertFalse(availableDenied.hasBackgroundPermission)
        assertEquals(BackgroundPermissionNotice.DENIED, availableDenied.notice)

        val availableRevoked = summarizeBackgroundRead(
            support = BackgroundReadSupport.AVAILABLE,
            grantedPermissions = emptySet(),
            previouslyGranted = true,
        )
        assertFalse(availableRevoked.hasBackgroundPermission)
        assertEquals(BackgroundPermissionNotice.REVOKED, availableRevoked.notice)

        val unsupported = summarizeBackgroundRead(
            support = BackgroundReadSupport.UNSUPPORTED,
            grantedPermissions = granted,
        )
        assertFalse(unsupported.hasBackgroundPermission)
        assertEquals(BackgroundPermissionNotice.NONE, unsupported.notice)
    }

    @Test
    fun confirmedMetricsContainCoreTypes() {
        val expected = setOf(
            HealthMetric.STEPS,
            HealthMetric.HEART_RATE,
            HealthMetric.DISTANCE,
            HealthMetric.TOTAL_CALORIES_BURNED,
            HealthMetric.SLEEP,
        )
        assertEquals(expected, BackgroundHealthConnectConfiguration.CONFIRMED_CORE_METRICS)
    }
}
