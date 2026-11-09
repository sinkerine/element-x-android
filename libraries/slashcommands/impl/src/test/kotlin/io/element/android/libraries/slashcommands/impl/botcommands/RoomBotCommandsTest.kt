/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.slashcommands.impl.botcommands

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.UserId
import org.junit.Test

class RoomBotCommandsTest {
    private val hermes = UserId("@hermes:a.org")
    private val helper = UserId("@helper:b.org")
    private fun description(name: String, bot: UserId = hermes, aliases: List<String> = emptyList(), text: String = "") =
        BotCommandDescription(bot, name, aliases, text, emptyList(), 0)
    private fun build(descriptions: List<BotCommandDescription>, builtIn: (String) -> Boolean = { false }) =
        buildRoomBotCommands(descriptions, descriptions.associate { it.botUserId to "Bot ${it.botUserId.value}" }, builtIn)
    private fun resolve(commands: List<RoomBotCommand>, text: String, builtIn: (String) -> Boolean = { false }) =
        resolveBotCommand(commands, text, builtIn)

    @Test
    fun `dedupe newest then deterministic state key and event ID ties`() {
        val old = description("MODEL").copy(ts = 1)
        val first = description("model").copy(ts = 2, stateKey = "a", eventId = "a", description = "first")
        val sameKey = first.copy(eventId = "b", description = "later ID")
        val laterKey = first.copy(stateKey = "b", eventId = "0", description = "later key")
        val inputs = listOf(old, sameKey, laterKey, first)
        assertThat(build(inputs).single().description).isEqualTo(first)
        assertThat(build(inputs.reversed()).single().description).isEqualTo(first)
        assertThat(buildRoomBotCommands(inputs, emptyMap()) { false }).isEmpty()
    }

    @Test
    fun `commands sorted by ID and published name before per bot and room caps`() {
        val descriptions = listOf(hermes, helper, UserId("@z:c.org")).flatMap { bot ->
            (0..500).map { description("c${it.toString().padStart(3, '0')}", bot) }
        }.reversed()
        val commands = build(descriptions)
        assertThat(commands).hasSize(1000)
        assertThat(commands.count { it.description.botUserId == helper }).isEqualTo(500)
        assertThat(commands.count { it.description.botUserId == hermes }).isEqualTo(500)
        assertThat(commands.first().description.botUserId).isEqualTo(helper)
        assertThat(commands.last().description.command).isEqualTo("c499")
        assertThat(build(listOf(description("a"), description("Z"))).map { it.description.command }).containsExactly("Z", "a").inOrder()
    }

    @Test
    fun `qualifiers use colliding localparts and names fall back to user ID`() {
        val other = UserId("@HERMES:b.org")
        val commands = build(listOf(description("one"), description("two", other)))
        assertThat(commands.map { it.qualifier }).containsExactly("HERMES:b.org", "hermes:a.org")
        assertThat(resolve(commands, "/one@HERMES:A.ORG")!!.command.description.botUserId).isEqualTo(hermes)
        assertThat(resolve(commands, "/one@hermes")).isNull()
        val fallback = buildRoomBotCommands(listOf(description("one")), mapOf(hermes to "")) { false }.single()
        assertThat(fallback.botName).isEqualTo(hermes.value)
    }

    @Test
    fun `built ins and other bots aliases require qualifiers`() {
        val commands = build(listOf(description("topic", aliases = listOf("t")), description("other", helper, listOf("t")))) { it == "topic" }
        assertThat(commands.all { it.requiresQualifier }).isTrue()
        assertThat(commands.first { it.description.command == "topic" }.insertedCommand).isEqualTo("/topic@hermes")
        assertThat(resolve(commands, "/topic x") { it == "topic" }).isNull()
        assertThat(resolve(commands, "/topic x")!!.argsText).isEqualTo("x")
        assertThat(resolve(commands, "/topic@hermes x") { it == "topic" }!!.argsText).isEqualTo("x")
        assertThat(resolve(commands, "/t")).isNull()
        assertThat(resolve(commands, "/t@helper")!!.command.description.command).isEqualTo("other")
    }

    @Test
    fun `resolution handles aliases longest names boundaries unknown qualifiers and NBSP`() {
        val commands = build(listOf(description("model", aliases = listOf("m")), description("model set"), description("model set default")))
        assertThat(resolve(commands, "/MODEL SET DEFAULT\u00a0 \u00a0gpt  5 ")!!.argsText).isEqualTo("gpt  5 ")
        assertThat(resolve(commands, "/model set default x")!!.command.description.command).isEqualTo("model set default")
        assertThat(resolve(commands, "/M@HERMES:A.ORG\u00a0x")!!.command.description.command).isEqualTo("model")
        listOf("//model", "model", "/modelx", "/model@", "/model@unknown", "/missing").forEach {
            assertThat(resolve(commands, it)).isNull()
        }
        assertThat(commands.first().insertedCommand).isEqualTo("/model")
    }

    @Test
    fun `distinct entries with the same matching alias are ambiguous even from one bot`() {
        val commands = build(listOf(description("one", aliases = listOf("shared")), description("two", aliases = listOf("shared"))))
        assertThat(resolve(commands, "/shared")).isNull()
        assertThat(resolve(commands, "/shared@hermes")).isNotNull()
    }

    @Test
    fun `matcher uses substring position then key weight stable ties and dedupe`() {
        val descriptions = listOf(
            description("amodel"),
            description("model"),
            description("other", text = "model"),
            description("model-two", aliases = listOf("model")),
            description("last", aliases = listOf("model")),
        )
        val commands = descriptions.map { RoomBotCommand(it, "model bot", "hermes", false) }
        assertThat(matchBotCommands(commands, "MOD").map { it.description.command })
            .containsExactly("model", "model-two", "other", "amodel", "last")
            .inOrder()
        assertThat(matchBotCommands(commands, "")).containsExactlyElementsIn(commands).inOrder()
        assertThat(matchBotCommands(commands, "---")).isEmpty()
        assertThat(matchBotCommands(commands, "model-t").single().description.command).isEqualTo("model-two")
    }

    @Test
    fun `matcher qualified queries filter on qualifier or full ID prefix`() {
        val commands = build(listOf(description("reload-model"), description("reload-model", helper)))
        assertThat(matchBotCommands(commands, "reload-m@HER").single().description.botUserId).isEqualTo(hermes)
        assertThat(matchBotCommands(commands, "reload-m@helper:B").single().description.botUserId).isEqualTo(helper)
        assertThat(matchBotCommands(commands, "reload-m@unknown")).isEmpty()
        assertThat(matchBotCommands(commands, "reload-m@")).hasSize(2)
    }

    @Test
    fun `usage preserves published order`() {
        val schema = BotCommandSchema.Primitive(BotCommandPrimitiveType.STRING)
        assertThat(formatBotCommandUsage(listOf(BotCommandParameter("first", schema, "", true), BotCommandParameter("second", schema, "", false))))
            .isEqualTo("[first] <second>")
        assertThat(formatBotCommandUsage(emptyList())).isEmpty()
    }
}
