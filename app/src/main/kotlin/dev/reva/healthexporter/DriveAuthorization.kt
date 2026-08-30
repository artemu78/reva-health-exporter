package dev.reva.healthexporter

object DriveAuthorizationScopes {
    const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
    val requested: Set<String> = setOf(DRIVE_FILE)

    fun isNarrow(scopes: Set<String>): Boolean = scopes == requested
}

sealed interface DriveAuthorizationState {
    data object Disconnected : DriveAuthorizationState
    data object Connecting : DriveAuthorizationState
    data class Connected(val accountId: String) : DriveAuthorizationState
    data object UserActionRequired : DriveAuthorizationState
}

sealed interface DriveAuthorizationResult {
    data class Authorized(val accountId: String) : DriveAuthorizationResult
    data object Cancelled : DriveAuthorizationResult
    data object Denied : DriveAuthorizationResult
    data object Revoked : DriveAuthorizationResult
}

interface DriveAuthorizationGateway {
    fun launchAuthorization()
    fun disconnect()
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
        gateway.disconnect()
        update(DriveAuthorizationState.Disconnected)
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
