package dev.reva.healthexporter

import androidx.health.connect.client.HealthConnectFeatures
import java.time.Instant

enum class BackgroundReadSupport {
    AVAILABLE,
    UNSUPPORTED,
}

fun classifyBackgroundReadSupport(sdkFeatureStatus: Int): BackgroundReadSupport =
    when (sdkFeatureStatus) {
        HealthConnectFeatures.FEATURE_STATUS_AVAILABLE -> BackgroundReadSupport.AVAILABLE
        else -> BackgroundReadSupport.UNSUPPORTED
    }

enum class BackgroundPermissionNotice {
    NONE,
    DENIED,
    REVOKED,
}

data class BackgroundReadSummary(
    val support: BackgroundReadSupport,
    val hasBackgroundPermission: Boolean,
    val notice: BackgroundPermissionNotice = BackgroundPermissionNotice.NONE,
) {
    val isSupported: Boolean get() = support == BackgroundReadSupport.AVAILABLE
}

fun summarizeBackgroundRead(
    support: BackgroundReadSupport,
    grantedPermissions: Set<String>,
    requestDenied: Boolean = false,
    previouslyGranted: Boolean = false,
): BackgroundReadSummary {
    if (support != BackgroundReadSupport.AVAILABLE) {
        return BackgroundReadSummary(
            support = BackgroundReadSupport.UNSUPPORTED,
            hasBackgroundPermission = false,
            notice = BackgroundPermissionNotice.NONE,
        )
    }
    val hasPermission = BackgroundHealthConnectConfiguration.BACKGROUND_READ_PERMISSION in grantedPermissions
    val notice = when {
        requestDenied && !hasPermission -> BackgroundPermissionNotice.DENIED
        previouslyGranted && !hasPermission -> BackgroundPermissionNotice.REVOKED
        else -> BackgroundPermissionNotice.NONE
    }
    return BackgroundReadSummary(
        support = BackgroundReadSupport.AVAILABLE,
        hasBackgroundPermission = hasPermission,
        notice = notice,
    )
}

fun backgroundPermissionsToRequest(summary: BackgroundReadSummary): Set<String> =
    if (summary.support == BackgroundReadSupport.AVAILABLE && !summary.hasBackgroundPermission) {
        setOf(BackgroundHealthConnectConfiguration.BACKGROUND_READ_PERMISSION)
    } else {
        emptySet()
    }

enum class BackgroundReadOutcome {
    SUCCESS,
    RETRYABLE_FAILURE,
    USER_ACTION_REQUIRED,
    UNSUPPORTED,
}

data class BackgroundReadExecutionSummary(
    val outcome: BackgroundReadOutcome,
    val message: String,
    val totalRecords: Int = 0,
    val readTypesCount: Int = 0,
    val executionTimestamp: Instant? = null,
    val dataOrigins: Set<String> = emptySet(),
)
