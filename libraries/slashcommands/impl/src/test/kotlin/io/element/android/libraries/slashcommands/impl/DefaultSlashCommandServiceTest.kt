/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.slashcommands.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.featureflag.test.FakeFeatureFlagService
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.room.IntentionalMention
import io.element.android.libraries.matrix.api.room.RoomStateEvent
import io.element.android.libraries.matrix.api.room.StateEventType
import io.element.android.libraries.matrix.api.timeline.MsgType
import io.element.android.libraries.matrix.test.FakeHomeserverCapabilitiesProvider
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.room.FakeBaseRoom
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.room.aRoomMember
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.preferences.api.store.AppPreferencesStore
import io.element.android.libraries.preferences.test.InMemoryAppPreferencesStore
import io.element.android.libraries.slashcommands.api.BotCommandMessage
import io.element.android.libraries.slashcommands.api.SlashCommand
import io.element.android.libraries.slashcommands.impl.botcommands.RoomBotCommandsSource
import io.element.android.libraries.slashcommands.impl.rainbow.RainbowGenerator
import io.element.android.services.toolbox.api.strings.StringProvider
import io.element.android.services.toolbox.test.strings.FakeStringProvider
import io.element.android.tests.testutils.lambda.lambdaRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultSlashCommandServiceTest {
    @Test
    fun `getSuggestions filters by text and maps to suggestions`() = runTest {
        val stringProvider = FakeStringProvider(defaultResult = "desc")
        val prefs = InMemoryAppPreferencesStore(isDeveloperModeEnabled = false)
        val sut = createDefaultSlashCommandService(
            commandParser = CommandParser(
                appPreferencesStore = prefs,
                featureFlagService = FakeFeatureFlagService(
                    initialState = mapOf(
                        FeatureFlags.SlashCommand.key to true,
                    )
                ),
                stringProvider = stringProvider,
            ),
            stringProvider = stringProvider,
            appPreferencesStore = prefs,
        )
        val res = sut.getSuggestions("ra", isInThread = true)
        // Expect commands starting with "/ra" (case-insensitive) and that are allowed in threads
        assertThat(res).isNotEmpty()
        assertThat(res.first().description).isEqualTo("desc")
    }

    @Test
    fun `getSuggestions hides dev commands when developer mode disabled`() = runTest {
        val stringProvider = FakeStringProvider()
        val prefs = InMemoryAppPreferencesStore(isDeveloperModeEnabled = false)
        val sut = createDefaultSlashCommandService(appPreferencesStore = prefs, stringProvider = stringProvider)
        val all = sut.getSuggestions("crash", isInThread = true)
        assertThat(all).isEmpty()
    }

    @Test
    fun `getSuggestions returns empty list when the feature is enabled`() = runTest {
        val sut = createDefaultSlashCommandService(isFeatureEnabled = true)
        val all = sut.getSuggestions("me", isInThread = false)
        assertThat(all).isNotEmpty()
    }

    @Test
    fun `getSuggestions returns empty list when the feature is disabled`() = runTest {
        val sut = createDefaultSlashCommandService(isFeatureEnabled = false)
        val all = sut.getSuggestions("me", isInThread = false)
        assertThat(all).isEmpty()
    }

    @Test
    fun `getSuggestions for aliases`() = runTest {
        val stringProvider = FakeStringProvider()
        val prefs = InMemoryAppPreferencesStore(isDeveloperModeEnabled = false)
        val sut = createDefaultSlashCommandService(appPreferencesStore = prefs, stringProvider = stringProvider)
        val all = sut.getSuggestions("part", isInThread = true)
        assertThat(all).isEmpty()
    }

    @Test
    fun `getSuggestions shows dev commands when developer mode enabled`() = runTest {
        val stringProvider = FakeStringProvider()
        val prefs = InMemoryAppPreferencesStore(isDeveloperModeEnabled = true)
        val sut = createDefaultSlashCommandService(appPreferencesStore = prefs, stringProvider = stringProvider)
        val all = sut.getSuggestions("crash", isInThread = true)
        assertThat(all).isNotEmpty()
        assertThat(all.first().command).isEqualTo("/crash")
    }

    @Test
    fun `parse delegates to commandParser`() = runTest {
        val sut = createDefaultSlashCommandService()
        val res = sut.parse("test", null, false)
        assertThat(res).isEqualTo(SlashCommand.NotACommand)
    }

    @Test
    fun `proceedSendMessage delegate to commandExecutor`() = runTest {
        val sendMessage = lambdaRecorder { _: String, _: String?, _: List<IntentionalMention>, _: MsgType, _: Boolean ->
            Result.success(Unit)
        }
        val sut = createDefaultSlashCommandService()
        val sendRes = sut.proceedSendMessage(
            slashCommand = SlashCommand.SendPlainText("hi"),
            timeline = FakeTimeline().apply {
                sendMessageLambda = sendMessage
            },
        )
        assertThat(sendRes.isSuccess).isTrue()
        sendMessage.assertions().isCalledOnce()
    }

    @Test
    fun `canChangeDisplayName is respected in suggestions`() = runTest {
        var result = false
        val capabilitiesProvider = FakeHomeserverCapabilitiesProvider(
            canChangeDisplayName = { Result.success(result) },
        )
        val sut = createDefaultSlashCommandService(capabilitiesProvider = capabilitiesProvider)

        // Initially, with a disabled capability, the change display name command should not be in the suggestions
        var changeNameCommand = sut.getSuggestions("", isInThread = false)
            .find { it.command == Command.CHANGE_DISPLAY_NAME.command }
        assertThat(changeNameCommand).isNull()

        // When the capability is true, the command should be included in the suggestions
        result = true
        changeNameCommand = sut.getSuggestions("", isInThread = false)
            .find { it.command == Command.CHANGE_DISPLAY_NAME.command }
        assertThat(changeNameCommand).isNotNull()
    }

    @Test
    fun `proceedAdmin delegates to commandExecutor`() = runTest {
        val leaveRoomLambda = lambdaRecorder<Result<Unit>> {
            Result.success(Unit)
        }
        val sut = createDefaultSlashCommandService(
            commandExecutor = CommandExecutor(
                matrixClient = FakeMatrixClient(),
                joinedRoom = FakeJoinedRoom(
                    baseRoom = FakeBaseRoom(
                        leaveRoomLambda = leaveRoomLambda
                    ),
                ),
                rainbowGenerator = RainbowGenerator(),
                stringProvider = FakeStringProvider(),
            ),
        )
        val adminRes = sut.proceedAdmin(SlashCommand.LeaveRoom)
        assertThat(adminRes.isSuccess).isTrue()
        leaveRoomLambda.assertions().isCalledOnce()
    }

    @Test
    fun `bot rows follow built ins and use description string resources`() = runTest {
        val strings = FakeStringProvider()
        val source = botSource("topic", "A description")
        runCurrent()
        val sut = createDefaultSlashCommandService(stringProvider = strings, roomBotCommandsSource = source)
        val rows = sut.getSuggestions("top", false)
        assertThat(rows.map { it.command }).containsExactly("/topic", "/topic@hermes").inOrder()
        assertThat(rows.last().botUserId).isEqualTo(UserId("@hermes:example.org"))
        assertThat(rows.last().parameters).isEqualTo("[args]")
        assertThat(rows.last().description).isEqualTo("A stringA description, Hermes")
        assertThat(strings.lastResIdParam).isEqualTo(R.string.slash_command_bot_description)
        assertThat(sut.resolveBotCommand("/topic hello")).isNull()
        assertThat(sut.resolveBotCommand("/topic@hermes hello")).isEqualTo(BotCommandMessage(
            UserId("@hermes:example.org"),
            "/topic hello",
            """{"org.matrix.msc4391.command":{"command":"topic","arguments":{"args":"hello"}}}""",
        ))
        assertThat(sut.getSuggestions("", false).last().botUserId).isNotNull()
    }

    @Test
    fun `bot commands stay enabled with flag off and use fallback description resource`() = runTest {
        val strings = FakeStringProvider()
        val source = botSource("topic")
        runCurrent()
        val sut = createDefaultSlashCommandService(isFeatureEnabled = false, stringProvider = strings, roomBotCommandsSource = source)
        val rows = sut.getSuggestions("", false)
        assertThat(rows.map { it.command }).containsExactly("/topic")
        assertThat(rows.single().description).isEqualTo("A stringHermes")
        assertThat(strings.lastResIdParam).isEqualTo(R.string.slash_command_bot_no_description)
        assertThat(sut.resolveBotCommand("/topic hello")?.body).isEqualTo("/topic hello")
    }

    @Test
    fun `crash precedence follows developer mode and aliases retain built in precedence`() = runTest {
        val source = botSource("crash")
        runCurrent()
        for (developerMode in listOf(false, true)) {
            val sut = createDefaultSlashCommandService(
                appPreferencesStore = InMemoryAppPreferencesStore(isDeveloperModeEnabled = developerMode),
                roomBotCommandsSource = source,
            )
            assertThat(sut.resolveBotCommand("/crash") == null).isEqualTo(developerMode)
            assertThat(sut.getSuggestions("crash", false).last().command)
                .isEqualTo(if (developerMode) "/crash@hermes" else "/crash")
        }
        val aliasSource = botSource("kick")
        runCurrent()
        assertThat(createDefaultSlashCommandService(roomBotCommandsSource = aliasSource).resolveBotCommand("/kick")).isNull()
    }

    @Test
    fun `send bot command passes exact content mentions and reply to timeline`() = runTest {
        val message = BotCommandMessage(UserId("@hermes:example.org"), "/model gpt-5", "{\"extra\":{}}")
        val sut = createDefaultSlashCommandService()
        for (reply in listOf(null, EventId("${'$'}reply"))) {
            for (extra in listOf(null, message.extraContentJson)) {
                val send = lambdaRecorder { body: String, mentions: List<IntentionalMention>, json: String?, eventId: EventId? ->
                    assertThat(body).isEqualTo(message.body)
                    assertThat(mentions).containsExactly(IntentionalMention.User(message.botUserId))
                    assertThat(json).isEqualTo(extra)
                    assertThat(eventId).isEqualTo(reply)
                    Result.success(Unit)
                }
                val timeline = FakeTimeline().apply { sendPlainTextWithExtraContentLambda = send }
                assertThat(sut.sendBotCommand(message.copy(extraContentJson = extra), timeline, reply).isSuccess).isTrue()
                send.assertions().isCalledOnce()
            }
        }
    }

    @Test
    fun `resolution failures become null and cancellation propagates`() = runTest {
        val failingFlags = object : FeatureFlagService by FakeFeatureFlagService() {
            override suspend fun isFeatureEnabled(feature: io.element.android.libraries.featureflag.api.Feature): Boolean {
                error("synthetic failure")
            }
        }
        val sut = createDefaultSlashCommandService(featureFlagService = failingFlags)
        assertThat(sut.resolveBotCommand("/model")).isNull()
        val cancelled = createDefaultSlashCommandService(
            appPreferencesStore = object : AppPreferencesStore by InMemoryAppPreferencesStore() {
                override fun isDeveloperModeEnabledFlow() = kotlinx.coroutines.flow.flow<Boolean> { throw CancellationException() }
            },
        )
        try {
            cancelled.resolveBotCommand("/model")
            error("Expected cancellation")
        } catch (_: CancellationException) {
            // Cancellation must reach the caller.
        }
    }

    private fun TestScope.botSource(command: String, description: String = ""): RoomBotCommandsSource {
        val bot = UserId("@hermes:example.org")
        val events = MutableStateFlow(listOf(RoomStateEvent(
            eventType = StateEventType.Custom("org.matrix.msc4391.command_description"),
            stateKey = command,
            sender = bot,
            contentJson = """
                {"command":"$command","description":{"m.text":[{"body":"$description"}]},
                 "parameters":[{"key":"args","schema":{"schema_type":"primitive","type":"string"},"optional":true}]}
            """.trimIndent(),
            eventId = EventId("${'$'}description"),
            timestamp = 1L,
        )))
        return RoomBotCommandsSource(FakeJoinedRoom(
            baseRoom = FakeBaseRoom(getUpdatedMemberResult = { Result.success(aRoomMember(it, "Hermes")) }),
            roomCoroutineScope = backgroundScope,
            stateEventsFlowResult = { events },
        ))
    }

    private fun createDefaultSlashCommandService(
        isFeatureEnabled: Boolean = true,
        featureFlagService: FeatureFlagService = FakeFeatureFlagService(
            initialState = mapOf(
                FeatureFlags.SlashCommand.key to isFeatureEnabled,
            ),
        ),
        appPreferencesStore: AppPreferencesStore = InMemoryAppPreferencesStore(),
        stringProvider: StringProvider = FakeStringProvider(),
        commandParser: CommandParser = createCommandParser(
            featureFlagService = featureFlagService,
            appPreferencesStore = appPreferencesStore,
            stringProvider = stringProvider,
        ),
        commandExecutor: CommandExecutor = createCommandExecutor(
            stringProvider = stringProvider,
        ),
        capabilitiesProvider: FakeHomeserverCapabilitiesProvider = FakeHomeserverCapabilitiesProvider(),
        roomBotCommandsSource: RoomBotCommandsSource = RoomBotCommandsSource(FakeJoinedRoom()),
    ) = DefaultSlashCommandService(
        commandParser = commandParser,
        commandExecutor = commandExecutor,
        stringProvider = stringProvider,
        appPreferencesStore = appPreferencesStore,
        featureFlagService = featureFlagService,
        capabilitiesProvider = capabilitiesProvider,
        roomBotCommandsSource = roomBotCommandsSource,
    )
}
