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
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiImage
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiNode
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiValue
import io.element.android.libraries.interactiveui.core.session.InteractiveUiPhase
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    fun `new room session restores persisted settled carousel`() = runTest {
        val room = FakeJoinedRoom(roomCoroutineScope = backgroundScope)
        val store = InMemoryInteractiveUiStateStore()
        val content = carouselContent()
        val first = InteractiveUiSessionRegistry(room, store).sessionFor(content).session
        first.setCarouselIndex("carousel", 3)
        advanceTimeBy(200)
        runCurrent()
        // A new room object needs the saved state rather than the live room owner.
        val restored = InteractiveUiSessionRegistry(FakeJoinedRoom(roomCoroutineScope = backgroundScope), store).sessionFor(content).session
        assertThat(restored).isNotSameInstanceAs(first)
        assertThat(restored.state.value.carouselIndex["carousel"]).isEqualTo(3)
    }

    @Test
    fun `graph recreation before debounce shares entry drafts and one inflight send`() = runTest {
        val roomJob = Job(backgroundScope.coroutineContext[Job])
        val roomScope = CoroutineScope(backgroundScope.coroutineContext + roomJob)
        val response = CompletableDeferred<Unit>()
        var sends = 0
        val room = object : JoinedRoom by FakeJoinedRoom(roomCoroutineScope = roomScope) {
            override suspend fun sendRaw(eventType: String, content: String): Result<EventId> {
                sends++
                response.await()
                return Result.success(EventId("$" + "action"))
            }
        }
        val store = InMemoryInteractiveUiStateStore()
        val content = carouselContent()
        val first = InteractiveUiSessionRegistry(room, store).sessionFor(content)
        first.session.setCarouselIndex("carousel", 3)
        first.session.setValue("name", InteractiveUiValue.Text("Draft"))
        runCurrent()
        val jobsBeforeRecreation = roomJob.children.toList()
        val restored = InteractiveUiSessionRegistry(room, store).sessionFor(content)
        assertThat(restored).isSameInstanceAs(first)
        assertThat(restored.session.state.value.carouselIndex["carousel"]).isEqualTo(3)
        assertThat(restored.session.state.value.values["name"]).isEqualTo(InteractiveUiValue.Text("Draft"))
        assertThat(store.values).isEmpty()
        assertThat(roomJob.children.toList()).containsExactlyElementsIn(jobsBeforeRecreation)
        first.session.activate("send")
        restored.session.activate("send")
        runCurrent()
        assertThat(sends).isEqualTo(1)
        response.complete(Unit)
        runCurrent()
        roomJob.cancel()
        runCurrent()
    }

    @Test
    fun `distinct room instances with equal room ids never share live entries`() = runTest {
        val store = InMemoryInteractiveUiStateStore()
        val content = carouselContent()
        val firstRoom = FakeJoinedRoom(roomCoroutineScope = backgroundScope)
        val secondRoom = FakeJoinedRoom(roomCoroutineScope = backgroundScope)
        assertThat(firstRoom.roomId).isEqualTo(secondRoom.roomId)
        val first = InteractiveUiSessionRegistry(firstRoom, store).sessionFor(content)
        first.session.setCarouselIndex("carousel", 3)
        val second = InteractiveUiSessionRegistry(secondRoom, store).sessionFor(content)
        assertThat(second).isNotSameInstanceAs(first)
        assertThat(second.session.state.value.carouselIndex).isEmpty()
    }

    @Test
    fun `room cancellation before debounce flushes once disposes and releases shared entries`() = runTest {
        val roomJob = Job(backgroundScope.coroutineContext[Job])
        val roomScope = CoroutineScope(backgroundScope.coroutineContext + roomJob)
        val backing = InMemoryInteractiveUiStateStore()
        var saves = 0
        val store = object : InteractiveUiStateStore by backing {
            override suspend fun save(roomId: io.element.android.libraries.matrix.api.core.RoomId, eventId: EventId, envelope: JsonObject) {
                saves++
                backing.save(roomId, eventId, envelope)
            }
        }
        val content = carouselContent()
        val room = FakeJoinedRoom(roomCoroutineScope = roomScope)
        val firstRegistry = InteractiveUiSessionRegistry(room, store)
        val first = firstRegistry.sessionFor(content).session
        first.setCarouselIndex("carousel", 3)
        assertThat(InteractiveUiSessionRegistry(room, store).sessionFor(content).session).isSameInstanceAs(first)
        roomJob.cancel()
        runCurrent()
        assertThat(saves).isEqualTo(1)
        assertThat(roomJob.children.toList()).isEmpty()
        first.setCarouselIndex("carousel", 1)
        assertThat(first.state.value.carouselIndex["carousel"]).isEqualTo(3)
        // The old owner cleared its entries; the global weak lookup also released that owner.
        val afterCancellation = InteractiveUiSessionRegistry(room, store).sessionFor(content).session
        assertThat(afterCancellation).isNotSameInstanceAs(first)
        val restored = InteractiveUiSessionRegistry(FakeJoinedRoom(roomCoroutineScope = backgroundScope), store).sessionFor(content).session
        assertThat(restored.state.value.carouselIndex["carousel"]).isEqualTo(3)
    }

    private fun carouselContent() = interactiveUiContent().let { content ->
        val model = content.model as InteractiveUiTimelineModel.Valid
        val carousel = InteractiveUiNode.Carousel(
            "carousel",
            null,
            null,
            "Products",
            (1..5).map {
                InteractiveUiNode.Carousel.Item(
                    "item$it",
                    InteractiveUiImage("Product $it", "mxc://test/$it", null, "image/png", null, null),
                    "Product $it",
                    null,
                    emptyList(),
                    null,
                    null
                )
            }
        )
        val root = model.payload.nodesById.getValue(model.payload.root) as InteractiveUiNode.Stack
        content.copy(model = model.copy(payload = model.payload.copy(nodes = model.payload.nodes.map {
            if (it.id == root.id) root.copy(children = root.children + carousel.id) else it
        } + carousel)))
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
