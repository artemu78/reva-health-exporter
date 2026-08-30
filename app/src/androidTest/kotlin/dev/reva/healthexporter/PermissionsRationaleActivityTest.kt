package dev.reva.healthexporter

import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionsRationaleActivityTest {
    @Test
    fun rationale_explains_selected_read_access_and_local_diagnostic_scope() {
        ActivityScenario.launch(PermissionsRationaleActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(
                    "Why Reva Health Exporter needs access",
                    activity.findViewById<TextView>(R.id.permissions_rationale_title).text.toString(),
                )
                assertEquals(
                    "The app reads only the selected Health Connect record types to show which health data is available for diagnostics. Health data stays on this phone during this phase and is not exported. You can deny or revoke access at any time.",
                    activity.findViewById<TextView>(R.id.permissions_rationale_body).text.toString(),
                )
            }
        }
    }
}
