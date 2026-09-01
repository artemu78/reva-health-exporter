package dev.reva.healthexporter

object DriveAuthorizationScopes {
    const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
    val requested: Set<String> = setOf(DRIVE_FILE)

    fun isNarrow(scopes: Set<String>): Boolean = scopes == requested
}

internal sealed interface DriveAuthorizationNextStep {
    data object LaunchResolution : DriveAuthorizationNextStep
    data object Authorized : DriveAuthorizationNextStep
    data object Denied : DriveAuthorizationNextStep
}

internal fun decideDriveAuthorizationNextStep(
    hasResolution: Boolean,
    grantedScopes: Set<String>,
): DriveAuthorizationNextStep = when {
    hasResolution -> DriveAuthorizationNextStep.LaunchResolution
    DriveAuthorizationScopes.isNarrow(grantedScopes) -> DriveAuthorizationNextStep.Authorized
    else -> DriveAuthorizationNextStep.Denied
}

sealed interface DriveAuthorizationState {
    data object Disconnected : DriveAuthorizationState
    data object Connecting : DriveAuthorizationState
    data class Connected(val accountId: String?) : DriveAuthorizationState
    data object Disconnecting : DriveAuthorizationState
    data object UserActionRequired : DriveAuthorizationState
}

sealed interface DriveAuthorizationResult {
    data class Authorized(val accountId: String?) : DriveAuthorizationResult
    data object Cancelled : DriveAuthorizationResult
    data object Denied : DriveAuthorizationResult
    data object Revoked : DriveAuthorizationResult
}

sealed interface DriveDisconnectionResult {
    data object Disconnected : DriveDisconnectionResult
    data object Failed : DriveDisconnectionResult
}

interface DriveAuthorizationGateway {
    fun launchAuthorization()
    fun disconnect(onComplete: (DriveDisconnectionResult) -> Unit)
}

class DriveAuthorizationCoordinator(
    private val gateway: DriveAuthorizationGateway,
    initialState: DriveAuthorizationState = DriveAuthorizationState.Disconnected,
    private val onStateChanged: (DriveAuthorizationState) -> Unit = {},
) {
    var state: DriveAuthorizationState = initialState
        private set
    private var stateBeforeLaunch: DriveAuthorizationState = initialState

    fun connect() = launch()

    fun reconnect() = launch()

    fun observeAuthorizationRequired() {
        update(DriveAuthorizationState.UserActionRequired)
    }

    fun complete(result: DriveAuthorizationResult) {
        update(
            when (result) {
                is DriveAuthorizationResult.Authorized -> DriveAuthorizationState.Connected(result.accountId)
                DriveAuthorizationResult.Cancelled ->
                    stateBeforeLaunch.takeIf { it is DriveAuthorizationState.Connected }
                        ?: DriveAuthorizationState.Disconnected
                DriveAuthorizationResult.Denied,
                DriveAuthorizationResult.Revoked,
                -> DriveAuthorizationState.UserActionRequired
            },
        )
    }

    fun disconnect() {
        update(DriveAuthorizationState.Disconnected)
        try {
            gateway.disconnect { }
        } catch (_: Exception) {
            // Local authorization state is already cleared; remote revocation is best-effort.
        }
    }

    private fun launch() {
        stateBeforeLaunch = state
        update(DriveAuthorizationState.Connecting)
        gateway.launchAuthorization()
    }

    private fun update(newState: DriveAuthorizationState) {
        state = newState
        onStateChanged(newState)
    }
}
