/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.slashcommands.impl.botcommands

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.room.RoomMembershipState
import io.element.android.libraries.matrix.api.room.RoomStateEvent
import io.element.android.libraries.matrix.api.room.StateEventType
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.room.FakeBaseRoom
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.room.aRoomMember
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RoomBotCommandsSourceTest {
    private val bot = UserId("@hermes:example.org")
    private val type = StateEventType.Custom(BOT_COMMAND_DESCRIPTION_EVENT_TYPE)

    private fun event(
        command: String = "model",
        sender: UserId = bot,
        id: String = "description",
        stateKey: String = command,
        timestamp: Long = 1L,
    ) = RoomStateEvent(type, stateKey, sender, """{"command":"$command","parameters":[]}""", EventId("$$id"), timestamp)

    @Test
    fun `self left failed and malformed senders are excluded and members refreshed once per sender`() = runTest {
        val left = UserId("@left:example.org")
        val failed = UserId("@failed:example.org")
        val calls = mutableListOf<UserId>()
        var joined = true
        val source = RoomBotCommandsSource(FakeJoinedRoom(
            baseRoom = FakeBaseRoom(getUpdatedMemberResult = {
                calls += it
                if (it == failed) {
                    Result.failure(IllegalStateException("synthetic"))
                } else {
                    Result.success(aRoomMember(
                        it,
                        membership = if (it == left || !joined) RoomMembershipState.LEAVE else RoomMembershipState.JOIN,
                    ))
                }
            }),
            roomCoroutineScope = backgroundScope,
            stateEventsFlowResult = {
                assertThat(it).isEqualTo(type)
                MutableStateFlow(listOf(
                    event(),
                    event("help", id = "help"),
                    event(sender = A_SESSION_ID, id = "self"),
                    event(sender = left, id = "left"),
                    event(sender = failed, id = "failed"),
                    event(id = "bad").copy(contentJson = "not JSON"),
                ))
            },
        ))
        runCurrent()
        assertThat(source.commands { false }.map { it.insertedCommand }).containsExactly("/help", "/model").inOrder()
        assertThat(source.commands { false }.first().botName).isEqualTo(bot.value)
        assertThat(calls).containsExactly(bot, left, failed, bot, left, failed).inOrder()
        joined = false
        assertThat(source.commands { false }).isEmpty()
    }

    @Test
    fun `snapshot updates blanking and cache eviction follow current state IDs`() = runTest {
        val original = event()
        val events = MutableStateFlow(listOf(original))
        val source = RoomBotCommandsSource(FakeJoinedRoom(
            baseRoom = FakeBaseRoom(getUpdatedMemberResult = { Result.success(aRoomMember(it, "Hermes", isNameAmbiguous = true)) }),
            roomCoroutineScope = backgroundScope,
            stateEventsFlowResult = { events },
        ))
        runCurrent()
        assertThat(source.commands { false }.single().botName).isEqualTo("Hermes ($bot)")
        // Event IDs are immutable: repeated snapshots reuse the parsed event.
        events.value = listOf(original.copy(contentJson = "{}"))
        runCurrent()
        assertThat(source.commands { false }.single().description.command).isEqualTo("model")
        events.value = listOf(event(id = "blank").copy(contentJson = "{}"))
        runCurrent()
        assertThat(source.commands { false }).isEmpty()
        // Unusable descriptions are memoised too.
        events.value = listOf(event("help", id = "blank"))
        runCurrent()
        assertThat(source.commands { false }).isEmpty()
        // The old ID was evicted, so it is parsed again if observed later.
        events.value = listOf(event("help", id = "description"))
        runCurrent()
        assertThat(source.commands { false }.single().description.command).isEqualTo("help")
        events.value = listOf(event("new", id = "new").copy(
            contentJson = """{"command":"new","parameters":[],"description":{"m.text":[{"body":"Updated text"}]}}""",
        ))
        runCurrent()
        assertThat(source.commands { false }.single().description.command).isEqualTo("new")
        assertThat(source.commands { false }.single().description.description).isEqualTo("Updated text")
        events.value = emptyList()
        runCurrent()
        assertThat(source.commands { false }).isEmpty()
    }

    @Test
    fun `state keys and event IDs preserve deterministic ties and newest timestamp`() = runTest {
        val events = MutableStateFlow(listOf(event("MODEL", id = "z", stateKey = "z"), event(stateKey = "a")))
        val source = RoomBotCommandsSource(FakeJoinedRoom(
            baseRoom = FakeBaseRoom(getUpdatedMemberResult = { Result.success(aRoomMember(it)) }),
            roomCoroutineScope = backgroundScope,
            stateEventsFlowResult = { events },
        ))
        runCurrent()
        val description = source.commands { false }.single().description
        assertThat(description.command).isEqualTo("model")
        assertThat(description.stateKey).isEqualTo("a")
        assertThat(description.eventId).isEqualTo("${'$'}description")
        events.value = events.value + event("Model", id = "newer", timestamp = 2L)
        runCurrent()
        assertThat(source.commands { false }.single().description.command).isEqualTo("Model")
    }

    @Test
    fun `member lookup cancellation propagates`() = runTest {
        val source = RoomBotCommandsSource(FakeJoinedRoom(
            baseRoom = FakeBaseRoom(getUpdatedMemberResult = { Result.failure(CancellationException()) }),
            roomCoroutineScope = backgroundScope,
            stateEventsFlowResult = { MutableStateFlow(listOf(event())) },
        ))
        runCurrent()
        try {
            source.commands { false }
            error("Expected cancellation")
        } catch (_: CancellationException) {
            // Cancellation must reach the caller.
        }
    }
}
