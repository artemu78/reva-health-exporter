package dev.reva.healthexporter

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticResultsUiTest {
    @Test
    fun loading_empty_success_permission_and_error_states_are_visible() {
        val states = listOf(
            screen(DiagnosticScreenPhase.LOADING, "Loading diagnostics"),
            screen(DiagnosticScreenPhase.EMPTY, "No records found"),
            screen(DiagnosticScreenPhase.SUCCESS, "Results ready"),
            screen(DiagnosticScreenPhase.PERMISSION_DENIED, "Permission required"),
            screen(DiagnosticScreenPhase.ERROR, "Read failed", canRetry = true),
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                states.forEach { state ->
                    activity.renderDiagnostic(state)
                    assertEquals(
                        state.message,
                        activity.findViewById<TextView>(R.id.diagnostic_status).text.toString(),
                    )
                    assertEquals(
                        if (state.phase == DiagnosticScreenPhase.LOADING) View.VISIBLE else View.GONE,
                        activity.findViewById<ProgressBar>(R.id.diagnostic_progress).visibility,
                    )
                }
            }
        }
    }

    @Test
    fun refresh_and_time_window_controls_dispatch_semantic_actions() {
        val actions = mutableListOf<DiagnosticUiAction>()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.renderDiagnostic(screen(DiagnosticScreenPhase.SUCCESS, "Ready"), actions::add)

                val refresh = activity.findViewById<Button>(R.id.diagnostic_refresh)
                assertEquals("Refresh diagnostic results", refresh.contentDescription.toString())
                refresh.performClick()

                val sevenDays = activity.findViewById<RadioButton>(R.id.window_seven_days)
                assertEquals("Show results from the last 7 days", sevenDays.contentDescription.toString())
                sevenDays.performClick()

                assertEquals(
                    listOf(
                        DiagnosticUiAction.Refresh,
                        DiagnosticUiAction.SelectWindow(DiagnosticTimeWindow.SEVEN_DAYS),
                    ),
                    actions,
                )
            }
        }
    }

    @Test
    fun limited_preview_requires_explicit_action_and_has_an_accessible_label() {
        val hidden = screen(
            DiagnosticScreenPhase.SUCCESS,
            "Ready",
            row = row(previewVisible = false),
        )
        var action: DiagnosticUiAction? = null

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.renderDiagnostic(hidden) { action = it }
                val showPreview = activity.findByDescription("Show limited preview for Steps") as Button
                assertFalse(activity.hasText("2026-08-30T08:00:00Z"))

                showPreview.performClick()

                assertEquals(DiagnosticUiAction.RevealPreview(HealthMetric.STEPS), action)
                activity.renderDiagnostic(hidden.copy(rows = listOf(row(previewVisible = true))))
                val preview = activity.findByDescription("Limited preview for Steps") as TextView
                assertTrue(preview.text.toString().contains("2026-08-30T08:00:00Z"))
            }
        }
    }

    @Test
    fun every_summary_field_and_action_has_accessible_text() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.renderDiagnostic(
                    screen(DiagnosticScreenPhase.SUCCESS, "Ready", row = row(previewVisible = false)),
                )

                assertEquals(
                    "Diagnostic time window",
                    activity.findViewById<View>(R.id.diagnostic_time_window).contentDescription.toString(),
                )
                assertNotNull(activity.findByDescription("Summary for Steps"))
                assertNotNull(activity.findByDescription("Show limited preview for Steps"))
                assertTrue(activity.hasText("Status: Available"))
                assertTrue(activity.hasText("Count: 2 records"))
                assertTrue(activity.hasText("Time coverage: 2026-08-30T08:00:00Z → 2026-08-30T08:05:00Z"))
                assertTrue(activity.hasText("Origins: com.example.mi.fitness"))
            }
        }
    }

    @Test
    fun activity_recreation_preserves_the_last_valid_results() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.renderDiagnostic(
                    screen(DiagnosticScreenPhase.SUCCESS, "Saved valid results", row = row()),
                )
            }

            scenario.recreate()

            scenario.onActivity { activity ->
                assertEquals(
                    "Saved valid results",
                    activity.findViewById<TextView>(R.id.diagnostic_status).text.toString(),
                )
                assertNotNull(activity.findByDescription("Summary for Steps"))
            }
        }
    }

    @Test
    fun unavailable_provider_stops_loading_and_disables_diagnostic_refresh() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.renderDiagnostic(
                    screen(
                        DiagnosticScreenPhase.ERROR,
                        "Health Connect must be available before diagnostics can run.",
                        canRetry = false,
                    ),
                )

                assertEquals(
                    View.GONE,
                    activity.findViewById<ProgressBar>(R.id.diagnostic_progress).visibility,
                )
                assertFalse(activity.findViewById<Button>(R.id.diagnostic_refresh).isEnabled)
            }
        }
    }

    private fun screen(
        phase: DiagnosticScreenPhase,
        message: String,
        canRetry: Boolean = false,
        row: DiagnosticMetricRow? = null,
    ) = DiagnosticScreenState(
        phase = phase,
        selectedWindow = DiagnosticTimeWindow.TWENTY_FOUR_HOURS,
        message = message,
        rows = listOfNotNull(row),
        canRetry = canRetry,
    )

    private fun row(previewVisible: Boolean = false) = DiagnosticMetricRow(
        metric = HealthMetric.STEPS,
        status = "Available",
        count = "2 records",
        coverage = "2026-08-30T08:00:00Z → 2026-08-30T08:05:00Z",
        origins = "com.example.mi.fitness",
        availablePreviewLines = listOf(
            "2026-08-30T08:00:00Z → 2026-08-30T08:05:00Z · com.example.mi.fitness",
        ),
        previewVisible = previewVisible,
    )

    private fun MainActivity.findByDescription(description: String): View =
        requireNotNull(findViewById<View>(android.R.id.content).findByDescription(description))

    private fun View.findByDescription(description: String): View? {
        if (contentDescription?.toString() == description) return this
        if (this !is ViewGroup) return null
        return (0 until childCount).firstNotNullOfOrNull { index ->
            getChildAt(index).findByDescription(description)
        }
    }

    private fun MainActivity.hasText(expected: String): Boolean =
        findViewById<View>(android.R.id.content).hasText(expected)

    private fun View.hasText(expected: String): Boolean {
        if (this is TextView && text.toString() == expected) return true
        if (this !is ViewGroup) return false
        return (0 until childCount).any { index -> getChildAt(index).hasText(expected) }
    }
}
