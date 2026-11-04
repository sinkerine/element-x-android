/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.element.android.features.messages.impl.interactiveui

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.timeline.model.event.InteractiveUiTimelineModel
import io.element.android.libraries.interactiveui.core.action.InteractiveUiActionContentBuilder
import io.element.android.libraries.interactiveui.core.action.InteractiveUiEventContext
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiValue
import io.element.android.libraries.interactiveui.core.session.InteractiveUiPhase
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test

class InteractiveUiSessionRegistryTest {
    @Test
    fun `same event keeps session across revisions while other event gets independent session`() = runTest {
        val registry = InteractiveUiSessionRegistry(FakeJoinedRoom(roomCoroutineScope = backgroundScope), InMemoryInteractiveUiStateStore())
        val content = interactiveUiContent()
        val session = registry.sessionFor(content).session
        session.setValue("name", InteractiveUiValue.Text("Draft"))
        val edited = interactiveUiContent(1)
        assertThat(registry.sessionFor(edited).session).isSameInstanceAs(session)
        assertThat(session.state.value.payload.revision).isEqualTo(1L)
        assertThat(session.state.value.values["name"]).isEqualTo(InteractiveUiValue.Text("Draft"))
        val other = edited.copy(eventId = EventId("$" + "other"))
        assertThat(registry.sessionFor(other).session).isNotSameInstanceAs(session)
    }

    @Test
    fun `action uses raw room message and exact builder content including original id and thread`() = runTest {
        val sent = mutableListOf<Pair<String, JsonObject>>()
        val room = FakeJoinedRoom(roomCoroutineScope = backgroundScope, sendRawResult = { type, json ->
            sent += type to Json.parseToJsonElement(json).jsonObject
            Result.success(EventId("$" + "action"))
        })
        val registry = InteractiveUiSessionRegistry(room, InMemoryInteractiveUiStateStore())
        val content = interactiveUiContent(threadRootId = ThreadId("$" + "thread"))
        val payload = (content.model as InteractiveUiTimelineModel.Valid).payload
        val session = registry.sessionFor(content).session
        session.setValue("name", InteractiveUiValue.Text("Ada"))
        session.setValue("on", InteractiveUiValue.Bool(true))
        runCurrent()
        assertThat(sent).isEmpty()
        session.activate("send")
        session.activate("send")
        assertThat(session.state.value.phase).isEqualTo(InteractiveUiPhase.Sending)
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(sent).hasSize(1)
        assertThat(sent.single().first).isEqualTo("m.room.message")
        val actual = sent.single().second
        val nonce = actual.getValue("net.15cm.matrix.interactive_ui.action").jsonObject.getValue("nonce").jsonPrimitive.content
        val expected = InteractiveUiActionContentBuilder.build(InteractiveUiEventContext(
            content.eventId.value,
            content.senderId.value,
            content.threadRootId!!.value
        ), payload, "reply", mapOf("name" to InteractiveUiValue.Text("Ada")), "Respond", nonce)
        assertThat(actual).isEqualTo(expected)
        assertThat(nonce).matches("[A-Za-z0-9_-]{32}")
    }

    @Test
    fun `late persisted state only applies to pristine session and room cancellation flushes values`() = runTest {
        val loaded = CompletableDeferred<JsonObject>()
        val saved = mutableListOf<JsonObject>()
        val store = object : InteractiveUiStateStore {
            override fun peek(roomId: io.element.android.libraries.matrix.api.core.RoomId, eventId: EventId) = null
            override suspend fun load(roomId: io.element.android.libraries.matrix.api.core.RoomId, eventId: EventId) = loaded.await()
            override suspend fun save(roomId: io.element.android.libraries.matrix.api.core.RoomId, eventId: EventId, envelope: JsonObject) {
                saved += envelope
            }
        }
        val registry = InteractiveUiSessionRegistry(FakeJoinedRoom(roomCoroutineScope = backgroundScope), store)
        val content = interactiveUiContent()
        val pristine = registry.sessionFor(content).session
        val touched = registry.sessionFor(content.copy(eventId = EventId("$" + "touched"))).session
        touched.setValue("name", InteractiveUiValue.Text("Typed"))
        runCurrent()
        loaded.complete(buildJsonObject { put("name", "Restored") })
        runCurrent()
        assertThat(pristine.state.value.values["name"]).isEqualTo(InteractiveUiValue.Text("Restored"))
        assertThat(touched.state.value.values["name"]).isEqualTo(InteractiveUiValue.Text("Typed"))
        backgroundScope.cancel()
        runCurrent()
        assertThat(saved).hasSize(2)
        assertThat(saved.map { it.getValue("state").jsonObject.getValue("name").jsonPrimitive.content }).containsExactly("Restored", "Typed")
    }
}
