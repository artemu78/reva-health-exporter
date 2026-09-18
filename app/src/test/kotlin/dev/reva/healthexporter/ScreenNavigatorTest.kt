package dev.reva.healthexporter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenNavigatorTest {
    private val mainScreen = 100
    private val vaultsScreen = 200
    private val settingsScreen = 300

    @Test
    fun `initial state is main screen and back is disabled`() {
        val navigator = ScreenNavigator(mainScreen = mainScreen)

        assertEquals(mainScreen, navigator.currentScreen)
        assertTrue(navigator.backStack.isEmpty())
        assertFalse(navigator.canGoBack)
        assertNull(navigator.goBack())
    }

    @Test
    fun `navigating to secondary screen enables back and pushes main screen to history`() {
        val navigator = ScreenNavigator(mainScreen = mainScreen)

        assertTrue(navigator.navigateTo(vaultsScreen))
        assertEquals(vaultsScreen, navigator.currentScreen)
        assertEquals(listOf(mainScreen), navigator.backStack)
        assertTrue(navigator.canGoBack)
    }

    @Test
    fun `navigating between multiple secondary screens preserves history order`() {
        val navigator = ScreenNavigator(mainScreen = mainScreen)

        navigator.navigateTo(vaultsScreen)
        navigator.navigateTo(settingsScreen)

        assertEquals(settingsScreen, navigator.currentScreen)
        assertEquals(listOf(mainScreen, vaultsScreen), navigator.backStack)
        assertTrue(navigator.canGoBack)
    }

    @Test
    fun `goBack pops previous screens in reverse navigation order until main screen`() {
        val navigator = ScreenNavigator(mainScreen = mainScreen)

        navigator.navigateTo(vaultsScreen)
        navigator.navigateTo(settingsScreen)

        assertEquals(vaultsScreen, navigator.goBack())
        assertEquals(vaultsScreen, navigator.currentScreen)
        assertEquals(listOf(mainScreen), navigator.backStack)
        assertTrue(navigator.canGoBack)

        assertEquals(mainScreen, navigator.goBack())
        assertEquals(mainScreen, navigator.currentScreen)
        assertTrue(navigator.backStack.isEmpty())
        assertFalse(navigator.canGoBack)

        assertNull(navigator.goBack())
        assertEquals(mainScreen, navigator.currentScreen)
    }

    @Test
    fun `navigating to current screen is a no-op`() {
        val navigator = ScreenNavigator(mainScreen = mainScreen)

        navigator.navigateTo(vaultsScreen)
        assertFalse(navigator.navigateTo(vaultsScreen))
        assertEquals(vaultsScreen, navigator.currentScreen)
        assertEquals(listOf(mainScreen), navigator.backStack)
    }

    @Test
    fun `navigating directly to main screen clears history and disables back`() {
        val navigator = ScreenNavigator(mainScreen = mainScreen)

        navigator.navigateTo(vaultsScreen)
        navigator.navigateTo(settingsScreen)
        assertTrue(navigator.navigateTo(mainScreen))

        assertEquals(mainScreen, navigator.currentScreen)
        assertTrue(navigator.backStack.isEmpty())
        assertFalse(navigator.canGoBack)
        assertNull(navigator.goBack())
    }

    @Test
    fun `re-visiting a screen moves it to top of history without duplicate cycles`() {
        val navigator = ScreenNavigator(mainScreen = mainScreen)

        navigator.navigateTo(vaultsScreen)
        navigator.navigateTo(settingsScreen)
        navigator.navigateTo(vaultsScreen)

        assertEquals(vaultsScreen, navigator.currentScreen)
        assertEquals(listOf(mainScreen, settingsScreen), navigator.backStack)

        assertEquals(settingsScreen, navigator.goBack())
        assertEquals(mainScreen, navigator.goBack())
        assertFalse(navigator.canGoBack)
    }

    @Test
    fun `restoring on secondary screen with empty history falls back to main screen on back`() {
        val navigator = ScreenNavigator(
            mainScreen = mainScreen,
            initialScreen = settingsScreen,
            initialHistory = emptyList(),
        )

        assertEquals(settingsScreen, navigator.currentScreen)
        assertTrue(navigator.canGoBack)

        assertEquals(mainScreen, navigator.goBack())
        assertEquals(mainScreen, navigator.currentScreen)
        assertFalse(navigator.canGoBack)
    }

    @Test
    fun `restoring with existing history continues back navigation seamlessly`() {
        val navigator = ScreenNavigator(
            mainScreen = mainScreen,
            initialScreen = settingsScreen,
            initialHistory = listOf(mainScreen, vaultsScreen),
        )

        assertEquals(settingsScreen, navigator.currentScreen)
        assertEquals(listOf(mainScreen, vaultsScreen), navigator.backStack)
        assertTrue(navigator.canGoBack)

        assertEquals(vaultsScreen, navigator.goBack())
        assertEquals(mainScreen, navigator.goBack())
        assertFalse(navigator.canGoBack)
    }
}
