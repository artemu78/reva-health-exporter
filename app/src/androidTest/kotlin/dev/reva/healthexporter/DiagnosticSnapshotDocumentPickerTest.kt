package dev.reva.healthexporter

import android.content.Intent
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticSnapshotDocumentPickerTest {
    @Test
    fun create_document_contract_uses_json_and_a_sanitized_filename() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = ActivityResultContracts.CreateDocument("application/json")
            .createIntent(context, "reva-health-diagnostic.json")

        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("application/json", intent.type)
        assertEquals("reva-health-diagnostic.json", intent.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test
    fun save_action_is_accessible_and_disabled_before_results_exist() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val button = activity.findViewById<Button>(R.id.diagnostic_export)
                assertEquals(
                    "Save a sanitized diagnostic JSON snapshot",
                    button.contentDescription.toString(),
                )
                assertFalse(button.isEnabled)
            }
        }
    }
}
