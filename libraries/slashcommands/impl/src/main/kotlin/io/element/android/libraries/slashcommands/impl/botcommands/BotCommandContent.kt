/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.slashcommands.impl.botcommands

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val SERVER_NAME_PATTERN = "(?:[A-Za-z0-9.-]+|\\[[0-9A-Fa-f:]+\\])(?::[0-9]+)?"
private val serverNamePattern = Regex(SERVER_NAME_PATTERN)
private val integerPattern = Regex("-?[0-9]+")

private fun mapPrimitive(type: BotCommandPrimitiveType, text: String): JsonPrimitive? = when (type) {
    BotCommandPrimitiveType.STRING -> JsonPrimitive(text)
    BotCommandPrimitiveType.INTEGER -> text.takeIf(integerPattern::matches)?.toLongOrNull()
        ?.takeIf { it in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER }
        ?.let(::JsonPrimitive)
    BotCommandPrimitiveType.BOOLEAN -> when (text.lowercase()) {
        "true", "yes", "on" -> JsonPrimitive(true)
        "false", "no", "off" -> JsonPrimitive(false)
        else -> null
    }
    BotCommandPrimitiveType.SERVER_NAME -> text.takeIf(serverNamePattern::matches)?.let(::JsonPrimitive)
    BotCommandPrimitiveType.USER_ID, BotCommandPrimitiveType.ROOM_ALIAS -> {
        val prefix = if (type == BotCommandPrimitiveType.USER_ID) '@' else '#'
        val localpart = text.drop(1).substringBefore(':')
        text.takeIf {
            it.startsWith(prefix) && localpart.isNotEmpty() && localpart.none(Char::isWhitespace) &&
                ':' in it && serverNamePattern.matches(it.substringAfter(':'))
        }?.let(::JsonPrimitive)
    }
    BotCommandPrimitiveType.ROOM_ID, BotCommandPrimitiveType.EVENT_ID -> null
}

private fun mapValue(schema: BotCommandSchema, text: String): JsonPrimitive? = when (schema) {
    is BotCommandSchema.ArrayOf -> null
    is BotCommandSchema.Union -> schema.variants.firstNotNullOfOrNull { mapValue(it, text) }
    is BotCommandSchema.Primitive -> mapPrimitive(schema.type, text)
    is BotCommandSchema.Literal -> {
        val expected = when (val value = schema.value) {
            is BotCommandLiteralValue.Text -> JsonPrimitive(value.value)
            is BotCommandLiteralValue.Integer -> JsonPrimitive(value.value)
            is BotCommandLiteralValue.Bool -> JsonPrimitive(value.value)
        }
        mapPrimitive(schema.literalType, text)?.takeIf { it == expected }
    }
}

private fun unsupported(schema: BotCommandSchema): Boolean = when (schema) {
    is BotCommandSchema.ArrayOf -> true
    is BotCommandSchema.Union -> schema.variants.any(::unsupported)
    is BotCommandSchema.Primitive -> schema.type == BotCommandPrimitiveType.ROOM_ID || schema.type == BotCommandPrimitiveType.EVENT_ID
    is BotCommandSchema.Literal -> false
}

/** Maps free text in required-then-optional order, or returns null if it cannot be mapped cleanly. */
internal fun mapBotCommandArguments(parameters: List<BotCommandParameter>, argsText: String): JsonObject? {
    if (parameters.any { unsupported(it.schema) }) return null
    val ordered = parameters.filterNot { it.optional } + parameters.filter { it.optional }
    val entries = linkedMapOf<String, JsonPrimitive>()
    var rest = argsText.trimStart()
    ordered.forEachIndexed { index, parameter ->
        if (rest.isEmpty()) {
            if (!parameter.optional) return null
        } else {
            val takeRest = index == ordered.lastIndex && parameter.schema == BotCommandSchema.Primitive(BotCommandPrimitiveType.STRING)
            val token = if (takeRest) rest else rest.takeWhile { !it.isWhitespace() }
            entries[parameter.key] = mapValue(parameter.schema, token) ?: return null
            rest = rest.drop(token.length).trimStart()
        }
    }
    return if (rest.isEmpty()) JsonObject(entries) else null
}

/** Builds a canonical plain body and the optional MSC4391 extra-content block. */
internal fun createBotCommandMessage(resolved: ResolvedBotCommand): BotCommandPayload {
    val description = resolved.command.description
    val arguments = mapBotCommandArguments(description.parameters, resolved.argsText)
    return BotCommandPayload(
        botUserId = description.botUserId,
        body = "/${description.command}" + if (resolved.argsText.isNotEmpty()) " ${resolved.argsText}" else "",
        extraContentJson = arguments?.let {
            buildJsonObject {
                put(BOT_COMMAND_CONTENT_KEY, buildJsonObject {
                    put("command", description.command)
                    put("arguments", it)
                })
            }.toString()
        },
    )
}
