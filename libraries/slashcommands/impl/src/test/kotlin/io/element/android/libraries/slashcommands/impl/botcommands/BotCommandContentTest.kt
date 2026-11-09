/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.slashcommands.impl.botcommands

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.UserId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

class BotCommandContentTest {
    private fun primitive(type: BotCommandPrimitiveType) = BotCommandSchema.Primitive(type)
    private fun parameter(schema: BotCommandSchema, key: String = "value", optional: Boolean = false) = BotCommandParameter(key, schema, "", optional)
    private fun map(schema: BotCommandSchema, text: String) = mapBotCommandArguments(listOf(parameter(schema)), text)?.get("value")

    @Test
    fun `string preserves remainder but other values consume one token`() {
        assertThat(map(primitive(BotCommandPrimitiveType.STRING), " \u00a0hello  world ")).isEqualTo(JsonPrimitive("hello  world "))
        assertThat(map(primitive(BotCommandPrimitiveType.INTEGER), "1 2")).isNull()
        assertThat(map(primitive(BotCommandPrimitiveType.INTEGER), "\u00a01\u00a0")).isEqualTo(JsonPrimitive(1))
    }

    @Test
    fun `integers require decimal safe whole numbers`() {
        listOf("0", "-0", "001", "9007199254740991", "-9007199254740991").forEach {
            assertThat(map(primitive(BotCommandPrimitiveType.INTEGER), it)).isEqualTo(JsonPrimitive(it.toLong()))
        }
        listOf("+1", "1.0", "1e2", "9007199254740992", "-9007199254740992", "999999999999999999999999", "١").forEach {
            assertThat(map(primitive(BotCommandPrimitiveType.INTEGER), it)).isNull()
        }
    }

    @Test
    fun `booleans accept only Web spellings ignoring case`() {
        listOf("TRUE", "yes", "On").forEach { assertThat(map(primitive(BotCommandPrimitiveType.BOOLEAN), it)).isEqualTo(JsonPrimitive(true)) }
        listOf("FALSE", "no", "Off").forEach { assertThat(map(primitive(BotCommandPrimitiveType.BOOLEAN), it)).isEqualTo(JsonPrimitive(false)) }
        listOf("1", "0", "enabled").forEach { assertThat(map(primitive(BotCommandPrimitiveType.BOOLEAN), it)).isNull() }
    }

    @Test
    fun `Matrix identifiers use Web server patterns and whitespace rules`() {
        val valid = mapOf(
            BotCommandPrimitiveType.SERVER_NAME to listOf("example.org", "localhost:8448", "[2001:db8::1]:8448"),
            BotCommandPrimitiveType.USER_ID to listOf("@hermes:example.org", "@name/ok:[::1]:123", "@é:localhost"),
            BotCommandPrimitiveType.ROOM_ALIAS to listOf("#room:example.org", "#name/ok:[::1]"),
        )
        valid.forEach { (type, values) -> values.forEach { assertThat(map(primitive(type), it)).isEqualTo(JsonPrimitive(it)) } }
        val invalid = mapOf(
            BotCommandPrimitiveType.SERVER_NAME to listOf("é.org", "example.org:abc", "[xyz]", "a_b.org", "https://a"),
            BotCommandPrimitiveType.USER_ID to listOf("@a\u00a0b:example.org", "@:example.org", "a:example.org", "@a:bad_server"),
            BotCommandPrimitiveType.ROOM_ALIAS to listOf("#a\u00a0b:example.org", "#:example.org", "@a:example.org", "#a:bad_server"),
        )
        invalid.forEach { (type, values) -> values.forEach { assertThat(map(primitive(type), it)).isNull() } }
    }

    @Test
    fun `literal values compare parsed types and unions use their first successful variant`() {
        val literals = listOf(
            Triple(BotCommandSchema.Literal(BotCommandPrimitiveType.STRING, BotCommandLiteralValue.Text("yes")), "yes", JsonPrimitive("yes")),
            Triple(BotCommandSchema.Literal(BotCommandPrimitiveType.INTEGER, BotCommandLiteralValue.Integer(1)), "01", JsonPrimitive(1)),
            Triple(BotCommandSchema.Literal(BotCommandPrimitiveType.BOOLEAN, BotCommandLiteralValue.Bool(true)), "on", JsonPrimitive(true)),
        )
        literals.forEach { (schema, text, expected) ->
            assertThat(map(schema, text)).isEqualTo(expected)
            assertThat(map(schema, "wrong")).isNull()
        }
        val union = BotCommandSchema.Union(listOf(primitive(BotCommandPrimitiveType.INTEGER), primitive(BotCommandPrimitiveType.STRING)))
        assertThat(map(union, "12")).isEqualTo(JsonPrimitive(12))
        assertThat(map(union, "abc")).isEqualTo(JsonPrimitive("abc"))
        assertThat(map(union, "two words")).isNull()
        assertThat(map(BotCommandSchema.Union(listOf(primitive(BotCommandPrimitiveType.BOOLEAN))), "abc")).isNull()
    }

    @Test
    fun `unsupported schemas anywhere suppress mapping even when optional or another union branch matches`() {
        val unsupported = listOf(
            primitive(BotCommandPrimitiveType.ROOM_ID), primitive(BotCommandPrimitiveType.EVENT_ID),
            BotCommandSchema.ArrayOf(primitive(BotCommandPrimitiveType.STRING)),
            BotCommandSchema.Union(listOf(primitive(BotCommandPrimitiveType.STRING), primitive(BotCommandPrimitiveType.ROOM_ID))),
            BotCommandSchema.Union(listOf(primitive(BotCommandPrimitiveType.EVENT_ID), primitive(BotCommandPrimitiveType.STRING))),
        )
        unsupported.forEach {
            assertThat(map(it, "anything")).isNull()
            assertThat(mapBotCommandArguments(listOf(parameter(it, optional = true)), "")).isNull()
        }
    }

    @Test
    fun `required parameters precede optional ones and missing or leftover text rejects mapping`() {
        val parameters = listOf(
            parameter(primitive(BotCommandPrimitiveType.STRING), "optional", true),
            parameter(primitive(BotCommandPrimitiveType.INTEGER), "required"),
        )
        assertThat(mapBotCommandArguments(parameters, "12 hello world")).isEqualTo(Json.parseToJsonElement("""{"required":12,"optional":"hello world"}"""))
        assertThat(mapBotCommandArguments(parameters, "12")).isEqualTo(Json.parseToJsonElement("""{"required":12}"""))
        assertThat(mapBotCommandArguments(parameters, "")).isNull()
        assertThat(mapBotCommandArguments(emptyList(), "extra")).isNull()
        assertThat(mapBotCommandArguments(emptyList(), " \u00a0")).isEqualTo(Json.parseToJsonElement("{}"))
    }

    @Test
    fun `payload uses canonical command with optional extra content and preserves arguments`() {
        val bot = UserId("@hermes:example.org")
        val description = BotCommandDescription(
            bot, "Model", listOf("m"), "", listOf(parameter(primitive(BotCommandPrimitiveType.STRING), "name", true)), 0,
        )
        val command = RoomBotCommand(description, "Hermes", "hermes", true)
        val payload = createBotCommandMessage(ResolvedBotCommand(command, "gpt-5.5 --provider openrouter"))
        assertThat(payload.botUserId).isEqualTo(bot)
        assertThat(payload.body).isEqualTo("/Model gpt-5.5 --provider openrouter")
        assertThat(Json.parseToJsonElement(payload.extraContentJson!!)).isEqualTo(Json.parseToJsonElement(
            """{"org.matrix.msc4391.command":{"command":"Model","arguments":{"name":"gpt-5.5 --provider openrouter"}}}""",
        ))
        val empty = createBotCommandMessage(ResolvedBotCommand(command, ""))
        assertThat(empty.body).isEqualTo("/Model")
        assertThat(Json.parseToJsonElement(empty.extraContentJson!!)).isEqualTo(Json.parseToJsonElement(
            """{"org.matrix.msc4391.command":{"command":"Model","arguments":{}}}""",
        ))
        val unsupported = command.copy(description = description.copy(parameters = listOf(parameter(primitive(BotCommandPrimitiveType.ROOM_ID)))))
        val plain = createBotCommandMessage(ResolvedBotCommand(unsupported, "!room:example.org"))
        assertThat(plain.body).isEqualTo("/Model !room:example.org")
        assertThat(plain.extraContentJson).isNull()
    }
}
