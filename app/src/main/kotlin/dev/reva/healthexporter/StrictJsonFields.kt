package dev.reva.healthexporter

import com.google.gson.JsonObject
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneOffset

internal fun JsonObject.requiredString(name: String): String {
    val element = get(name) ?: throw InvalidExportSchemaException("Field '$name' is required")
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
        throw InvalidExportSchemaException("Field '$name' must be a string")
    }
    return element.asString.takeIf(String::isNotBlank)
        ?: throw InvalidExportSchemaException("Field '$name' must be a non-blank string")
}

internal fun JsonObject.optionalString(name: String): String? {
    val element = get(name) ?: return null
    if (element.isJsonNull) return null
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
        throw InvalidExportSchemaException("Field '$name' must be a string")
    }
    return element.asString.takeIf(String::isNotBlank)
}

internal fun JsonObject.requiredInt(name: String): Int {
    val element = get(name) ?: throw InvalidExportSchemaException("Field '$name' is required")
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
        throw InvalidExportSchemaException("Field '$name' must be an integer number")
    }
    val bigDecimal = try {
        element.asJsonPrimitive.asBigDecimal
    } catch (e: Exception) {
        throw InvalidExportSchemaException("Field '$name' must be an integer", e)
    }
    return try {
        bigDecimal.intValueExact()
    } catch (e: ArithmeticException) {
        throw InvalidExportSchemaException(
            "Field '$name' must be an exact integer without fraction or overflow: $bigDecimal",
            e,
        )
    }
}

internal fun JsonObject.optionalInt(name: String): Int? {
    val element = get(name) ?: return null
    if (element.isJsonNull) return null
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
        throw InvalidExportSchemaException("Field '$name' must be an integer number")
    }
    val bigDecimal = try {
        element.asJsonPrimitive.asBigDecimal
    } catch (e: Exception) {
        throw InvalidExportSchemaException("Field '$name' must be an integer", e)
    }
    return try {
        bigDecimal.intValueExact()
    } catch (e: ArithmeticException) {
        throw InvalidExportSchemaException(
            "Field '$name' must be an exact integer without fraction or overflow: $bigDecimal",
            e,
        )
    }
}

internal fun JsonObject.requiredLong(name: String): Long {
    val element = get(name) ?: throw InvalidExportSchemaException("Field '$name' is required")
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
        throw InvalidExportSchemaException("Field '$name' must be an integer number")
    }
    val bigDecimal = try {
        element.asJsonPrimitive.asBigDecimal
    } catch (e: Exception) {
        throw InvalidExportSchemaException("Field '$name' must be a number", e)
    }
    return try {
        bigDecimal.longValueExact()
    } catch (e: ArithmeticException) {
        throw InvalidExportSchemaException(
            "Field '$name' must be an exact integer without fraction or overflow: $bigDecimal",
            e,
        )
    }
}

internal fun JsonObject.optionalLong(name: String): Long? {
    val element = get(name) ?: return null
    if (element.isJsonNull) return null
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
        throw InvalidExportSchemaException("Field '$name' must be an integer number")
    }
    val bigDecimal = try {
        element.asJsonPrimitive.asBigDecimal
    } catch (e: Exception) {
        throw InvalidExportSchemaException("Field '$name' must be a number", e)
    }
    return try {
        bigDecimal.longValueExact()
    } catch (e: ArithmeticException) {
        throw InvalidExportSchemaException(
            "Field '$name' must be an exact integer without fraction or overflow: $bigDecimal",
            e,
        )
    }
}

internal fun JsonObject.requiredDouble(name: String): Double = number(name, required = true)!!

internal fun JsonObject.optionalDouble(name: String): Double? = number(name, required = false)

private fun JsonObject.number(name: String, required: Boolean): Double? {
    val element = get(name)
    if (element == null) {
        if (required) throw InvalidExportSchemaException("Field '$name' is required")
        return null
    }
    if (element.isJsonNull && !required) return null
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
        throw InvalidExportSchemaException("Field '$name' must be a number")
    }
    val value = try {
        element.asJsonPrimitive.asDouble
    } catch (e: Exception) {
        throw InvalidExportSchemaException("Field '$name' must be a number", e)
    }
    if (value.isNaN() || value.isInfinite()) {
        throw InvalidExportSchemaException("Field '$name' must be finite, got $value")
    }
    return value
}

internal fun JsonObject.requiredInstant(name: String): Instant {
    val stringValue = requiredString(name)
    return try {
        Instant.parse(stringValue)
    } catch (e: DateTimeException) {
        throw InvalidExportSchemaException("Field '$name' has invalid ISO-8601 instant format: '$stringValue'", e)
    }
}

internal fun JsonObject.optionalInstant(name: String): Instant? {
    val stringValue = optionalString(name) ?: return null
    return try {
        Instant.parse(stringValue)
    } catch (e: DateTimeException) {
        throw InvalidExportSchemaException("Field '$name' has invalid ISO-8601 instant format: '$stringValue'", e)
    }
}

internal fun JsonObject.optionalZoneOffset(name: String): ZoneOffset? {
    val stringValue = optionalString(name) ?: return null
    return try {
        ZoneOffset.of(stringValue)
    } catch (e: DateTimeException) {
        throw InvalidExportSchemaException("Field '$name' has invalid zone offset format: '$stringValue'", e)
    }
}

internal fun JsonObject.requiredStrings(name: String): List<String> {
    val element = get(name) ?: throw InvalidExportSchemaException("Field '$name' is required")
    if (!element.isJsonArray) {
        throw InvalidExportSchemaException("Field '$name' must be an array of strings")
    }
    return element.asJsonArray.map { item ->
        if (!item.isJsonPrimitive || !item.asJsonPrimitive.isString) {
            throw InvalidExportSchemaException("Array '$name' must contain string elements")
        }
        item.asString
    }
}
