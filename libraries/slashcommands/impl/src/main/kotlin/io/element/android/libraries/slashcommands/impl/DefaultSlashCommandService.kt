/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.slashcommands.impl

import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.matrix.api.HomeserverCapabilitiesProvider
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.room.IntentionalMention
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.preferences.api.store.AppPreferencesStore
import io.element.android.libraries.slashcommands.api.BotCommandMessage
import io.element.android.libraries.slashcommands.api.SlashCommand
import io.element.android.libraries.slashcommands.api.SlashCommandService
import io.element.android.libraries.slashcommands.api.SlashCommandSuggestion
import io.element.android.libraries.slashcommands.impl.botcommands.RoomBotCommandsSource
import io.element.android.libraries.slashcommands.impl.botcommands.createBotCommandMessage
import io.element.android.libraries.slashcommands.impl.botcommands.formatBotCommandUsage
import io.element.android.libraries.slashcommands.impl.botcommands.matchBotCommands
import io.element.android.libraries.slashcommands.impl.botcommands.resolveBotCommand
import io.element.android.services.toolbox.api.strings.StringProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

@ContributesBinding(RoomScope::class)
class DefaultSlashCommandService(
    private val commandParser: CommandParser,
    private val commandExecutor: CommandExecutor,
    private val stringProvider: StringProvider,
    private val appPreferencesStore: AppPreferencesStore,
    private val featureFlagService: FeatureFlagService,
    private val capabilitiesProvider: HomeserverCapabilitiesProvider,
    private val roomBotCommandsSource: RoomBotCommandsSource,
) : SlashCommandService {
    override suspend fun getSuggestions(
        text: String,
        isInThread: Boolean,
    ): List<SlashCommandSuggestion> {
        val builtInsEnabled = featureFlagService.isFeatureEnabled(FeatureFlags.SlashCommand)
        val isDeveloperModeEnabled = appPreferencesStore.isDeveloperModeEnabledFlow().first()
        val isBuiltIn = builtInMatcher(builtInsEnabled, isDeveloperModeEnabled)
        val builtIns = if (builtInsEnabled) getBuiltInSuggestions(text, isInThread, isDeveloperModeEnabled) else emptyList()
        val bots = matchBotCommands(roomBotCommandsSource.commands(isBuiltIn), text).map {
            SlashCommandSuggestion(
                command = it.insertedCommand,
                parameters = formatBotCommandUsage(it.description.parameters).ifEmpty { null },
                description = if (it.description.description.isEmpty()) {
                    stringProvider.getString(R.string.slash_command_bot_no_description, it.botName)
                } else {
                    stringProvider.getString(R.string.slash_command_bot_description, it.description.description, it.botName)
                },
                botUserId = it.description.botUserId,
            )
        }
        return builtIns + bots
    }

    private suspend fun getBuiltInSuggestions(
        text: String,
        isInThread: Boolean,
        isDeveloperModeEnabled: Boolean,
    ): List<SlashCommandSuggestion> {
        return Command.entries
            .asSequence()
            .filter { it.startsWith(text) }
            .filter { !isInThread || it.isAllowedInThread }
            .filter { !it.isDevCommand || isDeveloperModeEnabled }
            // Don't include the change display name commands if the user can't change their display name
            .run {
                val canUserChangeDisplayName = withTimeoutOrNull(5.seconds) {
                    capabilitiesProvider.canChangeDisplayName().getOrNull()
                } ?: false
                if (!canUserChangeDisplayName) {
                    filterNot { it == Command.CHANGE_DISPLAY_NAME || it == Command.CHANGE_DISPLAY_NAME_FOR_ROOM }
                } else {
                    this
                }
            }
            // Don't include the change avatar commands if the user can't change their avatar url
            .run {
                val canUserChangeAvatar = withTimeoutOrNull(5.seconds) {
                    capabilitiesProvider.canChangeAvatarUrl().getOrNull()
                } ?: false
                if (!canUserChangeAvatar) {
                    filterNot { it == Command.CHANGE_AVATAR || it == Command.CHANGE_AVATAR_FOR_ROOM }
                } else {
                    this
                }
            }
            .map {
                SlashCommandSuggestion(
                    command = it.command,
                    parameters = it.parameters,
                    description = stringProvider.getString(it.description),
                )
            }
            .toList()
    }

    private fun builtInMatcher(enabled: Boolean, developerMode: Boolean): (String) -> Boolean = { name ->
        enabled && Command.entries.any { it.matches("/$name") && (it != Command.CRASH_APP || developerMode) }
    }

    override suspend fun resolveBotCommand(plainText: String): BotCommandMessage? {
        return try {
            val isBuiltIn = builtInMatcher(
                featureFlagService.isFeatureEnabled(FeatureFlags.SlashCommand),
                appPreferencesStore.isDeveloperModeEnabledFlow().first(),
            )
            val resolved = resolveBotCommand(roomBotCommandsSource.commands(isBuiltIn), plainText, isBuiltIn) ?: return null
            val payload = createBotCommandMessage(resolved)
            BotCommandMessage(payload.botUserId, payload.body, payload.extraContentJson)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Bot command resolution failed (%s)", e.javaClass.simpleName)
            null
        }
    }

    override suspend fun sendBotCommand(message: BotCommandMessage, timeline: Timeline, repliedToEventId: EventId?): Result<Unit> =
        timeline.sendPlainTextWithExtraContent(
            message.body,
            listOf(IntentionalMention.User(message.botUserId)),
            message.extraContentJson,
            repliedToEventId,
        )

    override suspend fun parse(
        textMessage: CharSequence,
        formattedMessage: String?,
        isInThreadTimeline: Boolean,
    ): SlashCommand {
        return commandParser.parseSlashCommand(
            textMessage = textMessage,
            formattedMessage = formattedMessage,
            isInThreadTimeline = isInThreadTimeline,
        )
    }

    override suspend fun proceedSendMessage(
        slashCommand: SlashCommand.SlashCommandSendMessage,
        timeline: Timeline,
    ): Result<Unit> {
        return commandExecutor.proceedSendMessage(
            slashCommand = slashCommand,
            timeline = timeline,
        )
    }

    override suspend fun proceedAdmin(
        slashCommand: SlashCommand.SlashCommandAdmin,
    ): Result<Unit> {
        return commandExecutor.proceedAdmin(
            slashCommand = slashCommand,
        )
    }
}
