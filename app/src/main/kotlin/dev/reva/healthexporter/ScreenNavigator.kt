package dev.reva.healthexporter

class ScreenNavigator(
    val mainScreen: Int,
    initialScreen: Int = mainScreen,
    initialHistory: List<Int> = emptyList(),
) {
    var currentScreen: Int = initialScreen
        private set

    private val history = mutableListOf<Int>().apply { addAll(initialHistory) }

    val backStack: List<Int> get() = history.toList()

    val canGoBack: Boolean get() = currentScreen != mainScreen || history.isNotEmpty()

    fun navigateTo(screen: Int): Boolean {
        if (screen == currentScreen) return false
        history.remove(screen)
        history.add(currentScreen)
        if (screen == mainScreen) {
            history.clear()
        }
        currentScreen = screen
        return true
    }

    fun goBack(): Int? {
        if (!canGoBack) return null
        val target = if (history.isNotEmpty()) {
            history.removeAt(history.lastIndex)
        } else {
            mainScreen
        }
        if (target == mainScreen) {
            history.clear()
        }
        currentScreen = target
        return target
    }
}
