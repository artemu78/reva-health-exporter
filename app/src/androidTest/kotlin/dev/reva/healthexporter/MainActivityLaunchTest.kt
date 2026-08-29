package dev.reva.healthexporter

import android.widget.TextView
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
}
