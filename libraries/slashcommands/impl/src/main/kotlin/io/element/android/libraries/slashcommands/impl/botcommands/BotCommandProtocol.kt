/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.slashcommands.impl.botcommands

import io.element.android.libraries.matrix.api.core.UserId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs

internal const val BOT_COMMAND_DESCRIPTION_EVENT_TYPE = "org.matrix.msc4391.command_description"
internal const val BOT_COMMAND_CONTENT_KEY = "org.matrix.msc4391.command"
internal const val MAX_SAFE_INTEGER = 9007199254740991L

internal enum class BotCommandPrimitiveType(val wireName: String) {
    STRING("string"), INTEGER("integer"), BOOLEAN("boolean"), USER_ID("user_id"),
    SERVER_NAME("server_name"), ROOM_ALIAS("room_alias"), ROOM_ID("room_id"), EVENT_ID("event_id"),
}

internal sealed interface BotCommandLiteralValue {
    data class Text(val value: String) : BotCommandLiteralValue
    data class Integer(val value: Long) : BotCommandLiteralValue
    data class Bool(val value: Boolean) : BotCommandLiteralValue
}

internal sealed interface BotCommandSchema {
    data class Primitive(val type: BotCommandPrimitiveType) : BotCommandSchema
    data class Literal(val literalType: BotCommandPrimitiveType, val value: BotCommandLiteralValue) : BotCommandSchema
    data class Union(val variants: List<BotCommandSchema>) : BotCommandSchema
    data class ArrayOf(val items: BotCommandSchema) : BotCommandSchema
}

internal data class BotCommandParameter(val key: String, val schema: BotCommandSchema, val description: String, val optional: Boolean)
internal data class BotCommandDescription(
    val botUserId: UserId,
    val command: String,
    val aliases: List<String>,
    val description: String,
    val parameters: List<BotCommandParameter>,
    val ts: Long,
    // The state reader supplies source identity so equal timestamps do not depend on store iteration order.
    val stateKey: String = "",
    val eventId: String = "",
)

private val tokenPattern = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
private val keyPattern = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.description(): String =
    (((this["description"] as? JsonObject)?.get("m.text") as? JsonArray)?.firstOrNull() as? JsonObject)
        ?.get("body").text()?.take(200).orEmpty()

private enum class SchemaLevel { TOP, ITEM, VARIANT }

private fun parseSchema(element: JsonElement?, level: SchemaLevel = SchemaLevel.TOP): BotCommandSchema? {
    val value = element as? JsonObject ?: return null
    fun type(key: String) = BotCommandPrimitiveType.entries.find { it.wireName == value[key].text() }
    return when (value["schema_type"].text()) {
        "primitive" -> BotCommandSchema.Primitive(type("type") ?: return null)
        "literal" -> {
            val literalType = type("literal_type") ?: return null
            val primitive = value["value"] as? JsonPrimitive ?: return null
            val literal = when (literalType) {
                BotCommandPrimitiveType.STRING -> BotCommandLiteralValue.Text(primitive.text() ?: return null)
                BotCommandPrimitiveType.BOOLEAN -> {
                    if (primitive.isString) return null
                    BotCommandLiteralValue.Bool(primitive.booleanOrNull ?: return null)
                }
                BotCommandPrimitiveType.INTEGER -> {
                    if (primitive.isString) return null
                    val number = primitive.doubleOrNull ?: return null
                    if (!number.isFinite() || abs(number) > MAX_SAFE_INTEGER.toDouble() || number % 1.0 != 0.0) return null
                    BotCommandLiteralValue.Integer(number.toLong())
                }
                else -> return null
            }
            BotCommandSchema.Literal(literalType, literal)
        }
        "union" -> {
            if (level == SchemaLevel.VARIANT) return null
            val variants = value["variants"] as? JsonArray ?: return null
            if (variants.isEmpty()) return null
            BotCommandSchema.Union(variants.map { parseSchema(it, SchemaLevel.VARIANT) ?: return null })
        }
        "array" -> {
            if (level != SchemaLevel.TOP) return null
            BotCommandSchema.ArrayOf(parseSchema(value["items"], SchemaLevel.ITEM) ?: return null)
        }
        else -> null
    }
}

/** Validates one description; unusable content never interrupts composing. */
internal fun parseBotCommandDescription(sender: UserId, contentJson: String, timestamp: Long?): BotCommandDescription? {
    val content = try {
        Json.parseToJsonElement(contentJson) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    } ?: return null
    val command = content["command"].text() ?: return null
    val tokens = command.split(' ')
    if (tokens.size > 3 || tokens.any { !tokenPattern.matches(it) }) return null
    val aliases = if ("aliases" in content) content["aliases"] as? JsonArray ?: return null else JsonArray(emptyList())
    if (aliases.size > 10) return null
    val aliasNames = aliases.map { it.text()?.takeIf(tokenPattern::matches) ?: return null }
    val parameters = content["parameters"] as? JsonArray ?: return null
    if (parameters.size > 32) return null
    val keys = mutableSetOf<String>()
    val parsedParameters = parameters.map {
        val parameter = it as? JsonObject ?: return null
        val key = parameter["key"].text() ?: return null
        if (!keyPattern.matches(key) || !keys.add(key)) return null
        val optional = if ("optional" in parameter) {
            val primitive = parameter["optional"] as? JsonPrimitive ?: return null
            if (primitive.isString) return null
            primitive.booleanOrNull ?: return null
        } else false
        BotCommandParameter(key, parseSchema(parameter["schema"]) ?: return null, parameter.description(), optional)
    }
    return BotCommandDescription(sender, command, aliasNames, content.description(), parsedParameters, timestamp ?: 0)
}
