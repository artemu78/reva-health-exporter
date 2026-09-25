package dev.reva.healthexporter

import androidx.health.connect.client.records.ExerciseSessionRecord
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseTypeLabelTest {
    @Test
    fun everySdkExerciseConstantHasTheHumanReadableLabelAndCode() {
        // Reflection is test-only: release code must not depend on retained SDK field names.
        val fields = ExerciseSessionRecord::class.java.fields.filter {
            it.name.startsWith("EXERCISE_TYPE_") && it.type == Int::class.javaPrimitiveType
        }
        assertTrue(fields.isNotEmpty())
        fields.forEach { field ->
            val code = field.getInt(null)
            val label = field.name.removePrefix("EXERCISE_TYPE_")
                .replace('_', ' ').lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
            assertEquals(field.name, "$label ($code)", exerciseTypeLabel(code))
        }
    }

    @Test
    fun unknownCodesRemainVisible() {
        assertEquals("Unknown workout (-1)", exerciseTypeLabel(-1))
        assertEquals("Unknown workout (999)", exerciseTypeLabel(999))
    }
}
