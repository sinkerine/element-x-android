/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.slashcommands.impl.botcommands

import io.element.android.libraries.matrix.api.core.UserId

internal data class RoomBotCommand(
    val description: BotCommandDescription,
    val botName: String,
    val qualifier: String,
    val requiresQualifier: Boolean,
) {
    val insertedCommand: String get() = "/${description.command}" + if (requiresQualifier) "@$qualifier" else ""
}

internal data class ResolvedBotCommand(val command: RoomBotCommand, val argsText: String)

private fun BotCommandDescription.names() = (listOf(command) + aliases).map { it.lowercase() }
private fun UserId.localpart() = value.removePrefix("@").substringBefore(':')

internal fun buildRoomBotCommands(
    descriptions: List<BotCommandDescription>,
    joinedBotNames: Map<UserId, String>,
    isBuiltIn: (String) -> Boolean,
): List<RoomBotCommand> {
    val deduped = mutableMapOf<String, BotCommandDescription>()
    descriptions.sortedWith(compareBy({ it.stateKey }, { it.eventId })).forEach { description ->
        if (description.botUserId !in joinedBotNames) return@forEach
        val key = "${description.botUserId.value}\u0000${description.command.lowercase()}"
        if (deduped[key]?.let { it.ts >= description.ts } != true) deduped[key] = description
    }
    val counts = mutableMapOf<UserId, Int>()
    val commands = deduped.values.sortedWith(compareBy({ it.botUserId.value }, { it.command })).filter {
        val count = counts.getOrDefault(it.botUserId, 0)
        counts[it.botUserId] = count + 1
        count < 500
    }.take(1000)
    return commands.map { command ->
        val collides = commands.any {
            it.botUserId != command.botUserId && it.botUserId.localpart().equals(command.botUserId.localpart(), ignoreCase = true)
        }
        RoomBotCommand(
            description = command,
            botName = joinedBotNames[command.botUserId].orEmpty().ifEmpty { command.botUserId.value },
            qualifier = if (collides) command.botUserId.value.removePrefix("@") else command.botUserId.localpart(),
            requiresQualifier = command.names().any { name ->
                isBuiltIn(name) || commands.any { it.botUserId != command.botUserId && name in it.names() }
            },
        )
    }
}

internal fun resolveBotCommand(commands: List<RoomBotCommand>, text: String, isBuiltIn: (String) -> Boolean): ResolvedBotCommand? {
    if (!text.startsWith('/') || text.startsWith("//")) return null
    val input = text.drop(1)
    data class Match(val command: RoomBotCommand, val end: Int, val name: String, val qualified: Boolean)
    val matches = commands.flatMap { command ->
        command.description.names().mapNotNull { name ->
            if (!input.startsWith(name, ignoreCase = true)) return@mapNotNull null
            var end = name.length
            val qualified = input.getOrNull(end) == '@'
            if (qualified) {
                val qualifier = input.drop(end + 1).takeWhile { !it.isWhitespace() }
                if (qualifier.isEmpty() ||
                    (!qualifier.equals(command.qualifier, ignoreCase = true) &&
                        !qualifier.equals(command.description.botUserId.value.removePrefix("@"), ignoreCase = true))
                ) {
                    return@mapNotNull null
                }
                end += qualifier.length + 1
            }
            if (end < input.length && !input[end].isWhitespace()) return@mapNotNull null
            Match(command, end, name, qualified)
        }
    }.sortedByDescending { it.name.length }
    val match = matches.firstOrNull() ?: return null
    if (!match.qualified && (isBuiltIn(input.takeWhile { !it.isWhitespace() }) ||
            matches.any { it.name == match.name && it.command !== match.command })
    ) {
        return null
    }
    return ResolvedBotCommand(match.command, input.drop(match.end).trimStart())
}

internal fun matchBotCommands(commands: List<RoomBotCommand>, query: String): List<RoomBotCommand> {
    if (query.isEmpty()) return commands
    val name = query.substringBefore('@')
    val qualifier = if ('@' in query) query.substringAfter('@') else null
    fun normalize(value: String) = value.lowercase().replace(Regex("[^a-z0-9_]"), "")
    val normalized = normalize(name)
    if (normalized.isEmpty()) return emptyList()
    data class Match(val command: RoomBotCommand, val index: Int, val weight: Int)
    // Group identical keys before sorting, preserving QueryMatcher's insertion order for ties.
    val keys = linkedMapOf<String, MutableList<Pair<RoomBotCommand, Int>>>()
    commands.forEach { command ->
        listOf(command.description.command, command.description.description, command.botName, command.description.aliases.joinToString(" "))
            .forEachIndexed { weight, key ->
                if (key.isNotEmpty()) keys.getOrPut(normalize(key)) { mutableListOf() }.add(command to weight)
            }
    }
    return keys.flatMap { (key, candidates) ->
        val index = key.indexOf(normalized)
        if (index < 0) emptyList() else candidates.map { (command, weight) -> Match(command, index, weight) }
    }.sortedWith(compareBy({ it.index }, { it.weight })).map { it.command }.distinct().filter {
        qualifier == null || it.qualifier.startsWith(qualifier, ignoreCase = true) ||
            it.description.botUserId.value.removePrefix("@").startsWith(qualifier, ignoreCase = true)
    }
}

internal fun formatBotCommandUsage(parameters: List<BotCommandParameter>): String = parameters.joinToString(" ") {
    if (it.optional) "[${it.key}]" else "<${it.key}>"
}
