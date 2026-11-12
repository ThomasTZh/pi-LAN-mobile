package dev.pi.mobile.proto

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Lenient JSON accessors. The Pi host is a third-party process whose record
 * shape can grow; every read here returns null instead of throwing so unknown
 * or differently-typed fields never break the reducer.
 */
fun JsonObject.str(key: String): String? = when (val value = this[key]) {
    is JsonPrimitive -> if (value.isString) value.content else null
    else -> null
}

fun JsonObject.anyText(key: String): String? = when (val value = this[key]) {
    is JsonPrimitive -> if (value is JsonNull) null else value.content
    is JsonElement -> if (value is JsonNull) null else value.toString()
    else -> null
}

fun JsonObject.intOr(key: String): Int? = when (val value = this[key]) {
    // Node's stat numbers arrive as JSON doubles ("mtimeMs":1794497263456.789),
    // and plain toIntOrNull()/toLongOrNull() reject the decimal point.
    is JsonPrimitive -> value.content.toIntOrNull() ?: value.content.toDoubleOrNull()?.toInt()
    else -> null
}

fun JsonObject.longOr(key: String): Long? = when (val value = this[key]) {
    is JsonPrimitive -> value.content.toLongOrNull() ?: value.content.toDoubleOrNull()?.toLong()
    else -> null
}

fun JsonObject.doubleOr(key: String): Double? = when (val value = this[key]) {
    is JsonPrimitive -> value.content.toDoubleOrNull()
    else -> null
}

fun JsonObject.boolOr(key: String): Boolean? = when (val value = this[key]) {
    is JsonPrimitive -> value.content.toBooleanStrictOrNull()
    else -> null
}

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** String value when present, otherwise the compact JSON of the value. */
fun JsonObject.textOrJson(key: String): String? = when (val value = this[key]) {
    null, is JsonNull -> null
    is JsonPrimitive -> if (value.isString) value.content else value.toString()
    else -> value.toString()
}

/** First present string among [keys]. */
fun JsonObject.firstString(vararg keys: String): String? {
    for (key in keys) {
        str(key)?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return null
}

/** Error text from either an "error" string or an "error" object. */
fun JsonObject.errorText(): String? {
    val direct = str("error")
    if (!direct.isNullOrBlank()) return direct
    val nested = this["error"] as? JsonObject
    return nested?.firstString("message", "detail", "reason")
}

/** Renders assistant content blocks, tool results or plain strings as text. */
fun JsonElement?.renderText(): String? {
    when (this) {
        null, is JsonNull -> return null
        is JsonPrimitive -> return content
        is JsonArray -> return joinToString("\n") { it.renderText().orEmpty() }.trim().ifEmpty { null }
        is JsonObject -> {
            firstString("output", "text", "result", "stdout", "stderr", "message")?.let { return it }
            val content = arr("content")
            if (content != null) {
                val rendered = content.joinToString("\n") { it.renderText().orEmpty() }.trim()
                if (rendered.isNotEmpty()) return rendered
            }
            return toString()
        }
        else -> return null
    }
}
