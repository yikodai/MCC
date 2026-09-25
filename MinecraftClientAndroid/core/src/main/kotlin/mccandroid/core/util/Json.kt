package mccandroid.core.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * kotlinx.serialization JSON 的便捷访问扩展。
 *
 * 命名与用法刻意贴近 MCC 的 `Json.ParseJson(...)?["key"]?.GetStringValue()`，
 * 便于与上游代码逐行对照；所有访问器都作用于可空的 [JsonElement]，
 * 字段缺失时返回 null 而不是抛异常。
 */
object Json {
    val INSTANCE: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /** 解析 JSON 文本，失败返回 null（不抛异常） */
    fun parseOrNull(text: String?): JsonElement? {
        if (text.isNullOrBlank()) return null
        return try {
            INSTANCE.parseToJsonElement(text)
        } catch (_: Exception) {
            null
        }
    }
}

/** 取字符串值，非字符串/不存在返回 null */
fun JsonElement?.stringOrNull(): String? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> contentOrNull
    else -> null
}

fun JsonElement?.stringValue(): String = stringOrNull() ?: ""

fun JsonElement?.intValue(): Int? = (this as? JsonPrimitive)?.intOrNull

fun JsonElement?.longValue(): Long? = (this as? JsonPrimitive)?.longOrNull

fun JsonElement?.boolValue(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

fun JsonElement?.asArrayOrNull(): JsonArray? = this as? JsonArray

/**
 * 取对象的字段，非对象或字段不存在时返回 null。
 *
 * 显式通过 [Map] 接口读取，避免与外层的同名扩展递归。
 */
operator fun JsonElement?.get(key: String): JsonElement? {
    val obj = this as? JsonObject ?: return null
    val map: Map<String, JsonElement> = obj
    return map[key]
}

/** 取字段的字符串值 */
fun JsonElement?.str(key: String): String? = this[key].stringOrNull()

/** 取字段的整数值 */
fun JsonElement?.int(key: String): Int? = this[key].intValue()

/** 取字段的布尔值 */
fun JsonElement?.bool(key: String): Boolean? = this[key].boolValue()