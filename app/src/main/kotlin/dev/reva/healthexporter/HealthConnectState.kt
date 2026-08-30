package dev.reva.healthexporter

enum class ProviderAvailability {
    AVAILABLE,
    UNAVAILABLE,
    UPDATE_REQUIRED,
}

object ProviderSdkStatus {
    const val UNAVAILABLE = 1
    const val UPDATE_REQUIRED = 2
    const val AVAILABLE = 3
}

fun classifyProviderAvailability(sdkStatus: Int): ProviderAvailability = when (sdkStatus) {
    ProviderSdkStatus.AVAILABLE -> ProviderAvailability.AVAILABLE
    ProviderSdkStatus.UPDATE_REQUIRED -> ProviderAvailability.UPDATE_REQUIRED
    else -> ProviderAvailability.UNAVAILABLE
}

enum class HealthMetric(val displayName: String) {
    STEPS("Steps"),
    HEART_RATE("Heart rate"),
    RESTING_HEART_RATE("Resting heart rate"),
    SLEEP("Sleep"),
    DISTANCE("Distance"),
    TOTAL_CALORIES_BURNED("Total calories burned"),
    EXERCISE_SESSIONS("Exercise sessions"),
    OXYGEN_SATURATION("Oxygen saturation"),
}

enum class PermissionNotice {
    NONE,
    DENIED,
    REVOKED,
}

data class PermissionSummary(
    val granted: Set<HealthMetric>,
    val missing: Set<HealthMetric>,
    val notice: PermissionNotice,
)

fun summarizePermissions(
    required: Set<HealthMetric>,
    granted: Set<HealthMetric>,
    requestDenied: Boolean = false,
    previouslyGranted: Set<HealthMetric> = emptySet(),
): PermissionSummary {
    val selectedGranted = granted intersect required
    val missing = required - selectedGranted
    val notice = when {
        requestDenied && missing.isNotEmpty() -> PermissionNotice.DENIED
        previouslyGranted.containsAll(required) && missing.isNotEmpty() -> PermissionNotice.REVOKED
        else -> PermissionNotice.NONE
    }
    return PermissionSummary(selectedGranted, missing, notice)
}

enum class HealthConnectAction {
    INSTALL_PROVIDER,
    UPDATE_PROVIDER,
    REQUEST_PERMISSIONS,
}

data class HealthConnectScreenState(
    val message: String,
    val grantedPermissions: String? = null,
    val missingPermissions: String? = null,
    val action: HealthConnectAction? = null,
    val actionLabel: String? = null,
)

fun healthConnectScreenState(
    availability: ProviderAvailability,
    permissions: PermissionSummary? = null,
): HealthConnectScreenState = when (availability) {
        ProviderAvailability.UNAVAILABLE -> HealthConnectScreenState(
            message = "Health Connect is not installed. Install it to inspect health data.",
            action = HealthConnectAction.INSTALL_PROVIDER,
            actionLabel = "Install Health Connect",
        )
        ProviderAvailability.UPDATE_REQUIRED -> HealthConnectScreenState(
            message = "Health Connect must be updated before health data can be inspected.",
            action = HealthConnectAction.UPDATE_PROVIDER,
            actionLabel = "Update Health Connect",
        )
        ProviderAvailability.AVAILABLE -> HealthConnectScreenState(
            message = permissions?.statusMessage() ?: "Checking selected read permissions…",
            grantedPermissions = permissions?.let { "Granted: ${it.granted.metricNames()}" },
            missingPermissions = permissions?.let { "Missing: ${it.missing.metricNames()}" },
            action = permissions?.takeIf { it.missing.isNotEmpty() }
                ?.let { HealthConnectAction.REQUEST_PERMISSIONS },
            actionLabel = permissions?.takeIf { it.missing.isNotEmpty() }?.actionLabel(),
        )
    }

private fun PermissionSummary.statusMessage(): String = when {
    notice == PermissionNotice.DENIED ->
        "Permission request was denied. The app remains usable, but diagnostics are limited."
    notice == PermissionNotice.REVOKED ->
        "Health Connect permissions were revoked. Grant access again to continue diagnostics."
    missing.isEmpty() -> "All selected read permissions are granted."
    granted.isEmpty() -> "No selected read permissions are granted."
    else -> "Some selected read permissions are missing."
}

private fun PermissionSummary.actionLabel(): String = when (notice) {
    PermissionNotice.DENIED -> "Try again"
    PermissionNotice.REVOKED -> "Grant again"
    PermissionNotice.NONE -> if (granted.isEmpty()) {
        "Grant read permissions"
    } else {
        "Grant missing permissions"
    }
}

private fun Set<HealthMetric>.metricNames(): String =
    if (isEmpty()) "None" else sortedBy { it.ordinal }.joinToString { it.displayName }
