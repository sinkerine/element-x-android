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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

class BotCommandProtocolTest {
    private val sender = UserId("@hermes:example.org")
    private fun parse(json: String) = parseBotCommandDescription(sender, json, null)
    private fun content(command: String = "model", parameters: String = "[]", extra: String = "") =
        """{"command":"$command","parameters":$parameters$extra}"""
    private fun parameter(schema: String, key: String = "name", extra: String = "") =
        """{"key":"$key","schema":$schema$extra}"""
    private fun primitive(type: String) = """{"schema_type":"primitive","type":"$type"}"""
    private fun schemaValid(schema: String): Boolean = parse(content(parameters = "[${parameter(schema)}]")) != null

    @Test
    fun `descriptions are parsed without interpreting text`() {
        val json = content(
            command = "weather forecast",
            parameters = "[${parameter(primitive("string"), extra = ",\"optional\":true")}]",
            extra = """, "aliases":["weather"], "description":{"m.text":[{"body":"${"a".repeat(199)}😀tail"}]}""",
        )
        val parsed = parseBotCommandDescription(sender, json, 123)!!
        assertThat(parsed.botUserId).isEqualTo(sender)
        assertThat(parsed.command).isEqualTo("weather forecast")
        assertThat(parsed.aliases).containsExactly("weather")
        assertThat(parsed.description.length).isEqualTo(200)
        assertThat(parsed.parameters.single().optional).isTrue()
        assertThat(parsed.ts).isEqualTo(123)
        assertThat(parse(content())!!.ts).isEqualTo(0)
        assertThat(parse(content())!!.description).isEmpty()
        assertThat(parse(content(parameters = "[${parameter(primitive("string"), extra = ",\"description\":{\"m.text\":[{\"body\":\"help\"}]}")}]"))!!
            .parameters.single().description).isEqualTo("help")
    }

    @Test
    fun `MSC4391 ban example accepts arrays and unsupported mapping primitives`() {
        val json = """
            {
              "command": "ban",
              "parameters": [
                {"key":"target_room","schema":{"schema_type":"primitive","type":"room_id"},
                 "description":{"m.text":[{"body":"The room ID"}]}},
                {"key":"timeout_seconds","schema":{"schema_type":"primitive","type":"integer"},
                 "description":{"m.text":[{"body":"The timeout in seconds"}]}},
                {"key":"apply_to_policy","schema":{"schema_type":"primitive","type":"boolean"},"optional":true,
                 "description":{"m.text":[{"body":"Whether to apply this to the policy"}]}},
                {"key":"target_users","schema":{"schema_type":"array","items":{"schema_type":"primitive","type":"user_id"}},
                 "description":{"m.text":[{"body":"The user ID(s)"}]}}
              ],
              "description":{"m.text":[{"body":"An example command with arguments"}]},
              "extra":true
            }
        """.trimIndent()
        val draupnir = UserId("@draupnir:draupnir.space")
        val parsed = parseBotCommandDescription(draupnir, json, 42)!!
        assertThat(parsed.botUserId).isEqualTo(draupnir)
        assertThat(parsed.command).isEqualTo("ban")
        assertThat(parsed.description).isEqualTo("An example command with arguments")
        assertThat(parsed.aliases).isEmpty()
        assertThat(parsed.ts).isEqualTo(42)
        assertThat(parsed.parameters.map { it.key to it.optional }).containsExactly(
            "target_room" to false,
            "timeout_seconds" to false,
            "apply_to_policy" to true,
            "target_users" to false,
        ).inOrder()
        assertThat(parsed.parameters.last().schema).isEqualTo(
            BotCommandSchema.ArrayOf(BotCommandSchema.Primitive(BotCommandPrimitiveType.USER_ID)),
        )
        assertThat(parsed.parameters.last().description).isEqualTo("The user ID(s)")
    }

    @Test
    fun `command tokens aliases and root shapes are validated`() {
        listOf("{}", "not JSON", "[]", "null", "1", "\"text\"", "{\"command\":12,\"parameters\":[]}")
            .forEach { assertThat(parse(it)).isNull() }
        listOf("", " a", "a ", "a  b", "a b c d", "_a", "-a", "é", "a".repeat(65), "a\\tb")
            .forEach { assertThat(parse(content(it))).isNull() }
        listOf("a", "A0_-", "a b c", "a".repeat(64)).forEach { assertThat(parse(content(it))).isNotNull() }
        listOf("null", "{}", "[1]", "[\"a b\"]", "[\"_a\"]", (1..11).joinToString(prefix = "[", postfix = "]") { "\"a$it\"" })
            .forEach { assertThat(parse(content(extra = ",\"aliases\":$it"))).isNull() }
        assertThat(parse(content(extra = ",\"aliases\":" + (1..10).joinToString(prefix = "[", postfix = "]") { "\"a$it\"" }))).isNotNull()
        assertThat(parse("""{"command":"a"}""")).isNull()
    }

    @Test
    fun `parameter count key uniqueness optional and schema presence are validated`() {
        val string = primitive("string")
        listOf(
            "null",
            "{}",
            "[null]",
            "[{}]",
            "[${parameter(string)},${parameter(string)}]",
            (1..33).joinToString(prefix = "[", postfix = "]") { parameter(string, "key$it") },
        )
            .forEach { assertThat(parse(content(parameters = it))).isNull() }
        listOf("1key", "", "bad-key", "a".repeat(65), "é").forEach {
            assertThat(parse(content(parameters = "[${parameter(string, it)}]"))).isNull()
        }
        listOf("null", "1", "\"true\"", "{}").forEach {
            assertThat(parse(content(parameters = "[${parameter(string, extra = ",\"optional\":$it")}]"))).isNull()
        }
        assertThat(parse(content(parameters = (1..32).joinToString(prefix = "[", postfix = "]") { parameter(string, "_key$it") }))).isNotNull()
        assertThat(parse(content(parameters = "[${parameter(string, "a".repeat(64), ",\"optional\":false")}]"))!!
            .parameters.single().optional).isFalse()
    }

    @Test
    fun `primitive and literal validation uses JSON types and JS safe integers`() {
        BotCommandPrimitiveType.entries.forEach { assertThat(schemaValid(primitive(it.wireName))).isTrue() }
        listOf("null", "[]", "{}", primitive("unknown"), """{"schema_type":"primitive","type":1}""")
            .forEach { assertThat(schemaValid(it)).isFalse() }
        fun literal(type: String, value: String) = """{"schema_type":"literal","literal_type":"$type","value":$value}"""
        listOf(
            literal("string", "\"yes\""),
            literal("boolean", "false"),
            literal("integer", "1.0"),
            literal("integer", "-9007199254740991"),
            literal("integer", "9007199254740991"),
            literal("integer", "1e2"),
        )
            .forEach { assertThat(schemaValid(it)).isTrue() }
        listOf(
            literal("string", "1"),
            literal("boolean", "\"true\""),
            literal("integer", "\"1\""),
            literal("integer", "1.1"),
            literal("integer", "9007199254740992"),
            literal("integer", "-9007199254740992"),
            literal("user_id", "\"@a:b\""),
            literal("integer", "null"),
        )
            .forEach { assertThat(schemaValid(it)).isFalse() }
    }

    @Test
    fun `schemas allow only top level arrays and non nested unions`() {
        fun union(items: String) = """{"schema_type":"union","variants":[$items]}"""
        fun array(items: String) = """{"schema_type":"array","items":$items}"""
        val string = primitive("string")
        listOf(array(string), union(string), array(union(string))).forEach { assertThat(schemaValid(it)).isTrue() }
        listOf(
            union(""),
            union(union(string)),
            union(array(string)),
            array(array(string)),
            array(union(union(string))),
            """{"schema_type":"union","variants":{}}""",
            """{"schema_type":"array"}""",
        )
            .forEach { assertThat(schemaValid(it)).isFalse() }
    }

    @Test
    fun `malformed descriptions are empty rather than rejecting valid commands`() {
        listOf("null", "[]", "\"text\"", "{}", """{"m.text":[]}""", """{"m.text":[{"body":1}]}""").forEach {
            assertThat(parse(content(extra = ",\"description\":$it"))!!.description).isEmpty()
        }
        val root = Json.parseToJsonElement(content()) as JsonObject
        assertThat(parse(JsonObject(root + ("command" to JsonArray(listOf(JsonPrimitive("model"))))).toString())).isNull()
    }
}
