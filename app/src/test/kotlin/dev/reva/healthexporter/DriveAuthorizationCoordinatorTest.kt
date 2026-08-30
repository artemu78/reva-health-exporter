package dev.reva.healthexporter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveAuthorizationCoordinatorTest {
    private class FakeGateway : DriveAuthorizationGateway {
        var launchCount = 0
        var disconnectCount = 0

        override fun launchAuthorization() {
            launchCount += 1
        }

        override fun disconnect() {
            disconnectCount += 1
        }
    }

    private fun coordinator(gateway: FakeGateway = FakeGateway()) =
        DriveAuthorizationCoordinator(gateway) to gateway

    @Test
    fun `connect is launched only for an explicit user action`() {
        val (coordinator, gateway) = coordinator()

        coordinator.observeAuthorizationRequired()
        assertEquals(0, gateway.launchCount)
        assertEquals(DriveAuthorizationState.UserActionRequired, coordinator.state)

        coordinator.connect()
        assertEquals(1, gateway.launchCount)
        assertEquals(DriveAuthorizationState.Connecting, coordinator.state)
    }

    @Test
    fun `success cancellation denial and revocation have safe states`() {
        val (coordinator, _) = coordinator()

        coordinator.connect()
        coordinator.complete(DriveAuthorizationResult.Cancelled)
        assertEquals(DriveAuthorizationState.Disconnected, coordinator.state)

        coordinator.connect()
        coordinator.complete(DriveAuthorizationResult.Authorized("account-a"))
        assertEquals(DriveAuthorizationState.Connected("account-a"), coordinator.state)

        coordinator.reconnect()
        coordinator.complete(DriveAuthorizationResult.Cancelled)
        assertEquals(DriveAuthorizationState.Connected("account-a"), coordinator.state)

        coordinator.connect()
        coordinator.complete(DriveAuthorizationResult.Denied)
        assertEquals(DriveAuthorizationState.UserActionRequired, coordinator.state)

        coordinator.complete(DriveAuthorizationResult.Revoked)
        assertEquals(DriveAuthorizationState.UserActionRequired, coordinator.state)
    }

    @Test
    fun `disconnect clears local account and account switch replaces it`() {
        val (coordinator, gateway) = coordinator()
        coordinator.complete(DriveAuthorizationResult.Authorized("account-a"))

        coordinator.reconnect()
        coordinator.complete(DriveAuthorizationResult.Authorized("account-b"))
        assertEquals(DriveAuthorizationState.Connected("account-b"), coordinator.state)

        coordinator.disconnect()
        assertEquals(1, gateway.disconnectCount)
        assertEquals(DriveAuthorizationState.Disconnected, coordinator.state)
    }

    @Test
    fun `only drive file scope is accepted`() {
        assertTrue(DriveAuthorizationScopes.isNarrow(setOf(DriveAuthorizationScopes.DRIVE_FILE)))
        assertFalse(
            DriveAuthorizationScopes.isNarrow(
                setOf(DriveAuthorizationScopes.DRIVE_FILE, "https://www.googleapis.com/auth/drive"),
            ),
        )
        assertFalse(DriveAuthorizationScopes.isNarrow(emptySet()))
    }
}
