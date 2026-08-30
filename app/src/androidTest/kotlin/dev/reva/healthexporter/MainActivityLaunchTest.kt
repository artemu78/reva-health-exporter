package dev.reva.healthexporter

import android.widget.TextView
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityLaunchTest {
    @Test
    fun launcher_shows_application_name_on_first_screen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val applicationLabel = context.applicationInfo.loadLabel(context.packageManager).toString()

        assertEquals("Reva Health Exporter", applicationLabel)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val title = activity.findViewById<TextView>(R.id.first_screen_title)
                assertEquals("Reva Health Exporter", title.text.toString())
            }
        }
    }

    @Test
    fun launcher_shows_installed_version_at_the_bottom_of_the_screen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val installedVersion = context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val footer = activity.findViewById<TextView>(R.id.app_version)
                val parent = footer.parent as ViewGroup

                assertEquals("Version $installedVersion", footer.text.toString())
                assertEquals(parent.childCount - 1, parent.indexOfChild(footer))
            }
        }
    }
}
