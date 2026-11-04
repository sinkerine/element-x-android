/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.interactiveui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.AnnotatedString
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.timeline.model.event.InteractiveUiTimelineModel
import io.element.android.libraries.htmlrenderer.api.DocumentNode
import io.element.android.libraries.htmlrenderer.api.ParagraphNode
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiConstants
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiImage
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiNode
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiValue
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiRenderNode
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiViewEvent
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiViewState
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InteractiveUiPresenterTest {
    @Test
    fun `Molecule initial state events and revised content use same session`() = runTest {
        val registry = InteractiveUiSessionRegistry(FakeJoinedRoom(roomCoroutineScope = backgroundScope), InMemoryInteractiveUiStateStore())
        val content = mutableStateOf(interactiveUiContent())
        moleculeFlow(RecompositionMode.Immediate) { InteractiveUiPresenter(content.value, registry).present() }.test {
            val initial = awaitItem()
            assertThat(initial.title).isEqualTo("Card header")
            initial.eventSink(InteractiveUiViewEvent.SetValue("name", InteractiveUiValue.Text("Draft")))
            val updated = awaitItem()
            assertThat(((updated.root as InteractiveUiRenderNode.Stack).children[0] as InteractiveUiRenderNode.TextInput).value).isEqualTo("Draft")
            updated.eventSink(InteractiveUiViewEvent.SetShowAsText(true))
            assertThat(awaitItem().showAsText).isTrue()
            content.value = interactiveUiContent(1)
            val revised = awaitItem()
            assertThat(((revised.root as InteractiveUiRenderNode.Stack).children[0] as InteractiveUiRenderNode.TextInput).value).isEqualTo("Draft")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `presenters retain complete accepted model across equal and lower revisions and send accepted revision with original identity`() = runTest {
        val sent = mutableListOf<JsonObject>()
        val registry = InteractiveUiSessionRegistry(FakeJoinedRoom(roomCoroutineScope = backgroundScope, sendRawResult = { _, json ->
            sent += Json.parseToJsonElement(json).jsonObject
            Result.success(EventId("$" + "action"))
        }), InMemoryInteractiveUiStateStore())
        val original = contentWithAssets(2, "Accepted 2")
        val incoming = mutableStateOf(original)
        val observed = mutableListOf<Pair<InteractiveUiViewState, InteractiveUiViewState>>()
        moleculeFlow(RecompositionMode.Immediate) {
            InteractiveUiPresenter(original, registry).present() to InteractiveUiPresenter(incoming.value, registry).present()
        }.onEach(observed::add).launchIn(backgroundScope)
        runCurrent()
        observed.last().first.eventSink(InteractiveUiViewEvent.SetValue("name", InteractiveUiValue.Text("Draft")))
        runCurrent()
        assertAssets(observed.last().first, "Accepted 2")
        assertAssets(observed.last().second, "Accepted 2")

        for (ignored in listOf(contentWithAssets(2, "Ignored equal"), contentWithAssets(1, "Ignored lower"), contentWithAssets(1, null))) {
            incoming.value = ignored
            runCurrent()
            assertAssets(observed.last().first, "Accepted 2")
            assertAssets(observed.last().second, "Accepted 2")
        }
        observed.last().second.eventSink(InteractiveUiViewEvent.Activate("send"))
        advanceTimeBy(1_000)
        runCurrent()
        assertAction(sent.single(), 2, original.eventId, original.senderId, original.threadRootId!!)

        incoming.value = contentWithAssets(3, "Accepted 3").copy(senderId = UserId("@editor:test"), threadRootId = ThreadId("$" + "editThread"))
        runCurrent()
        assertAssets(observed.last().first, "Accepted 3")
        assertAssets(observed.last().second, "Accepted 3")
        observed.last().first.eventSink(InteractiveUiViewEvent.Activate("send"))
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(sent).hasSize(2)
        assertAction(sent.last(), 3, original.eventId, original.senderId, original.threadRootId)

        // Every emission from both presenters must pair its accepted payload with assets of that same revision.
        observed.flatMap { listOf(it.first, it.second) }.forEach { state ->
            assertThat(state.title).isAnyOf("Accepted 2", "Accepted 3")
            assertAssets(state, state.title!!)
        }
    }

    private fun contentWithAssets(revision: Long, assetLabel: String?) = interactiveUiContent(revision, ThreadId("$" + "thread")).let { content ->
        val model = content.model as InteractiveUiTimelineModel.Valid
        val root = model.payload.nodes.first() as InteractiveUiNode.Stack
        val label = assetLabel ?: "Removed assets"
        val image = InteractiveUiImage(label, "mxc://example.org/$revision", null, "image/png", 100, 100)
        content.copy(model = model.copy(
            payload = model.payload.copy(title = label, nodes = listOf(root.copy(children = root.children + listOf("rich", "image"))) +
                model.payload.nodes.drop(1) + listOf(
                    InteractiveUiNode.RichText("rich", null, null, label, "<b>$label</b>"),
                    InteractiveUiNode.Image("image", null, null, image),
                )),
            richText = if (assetLabel == null) persistentMapOf() else persistentMapOf("rich" to document(label)),
            media = if (assetLabel == null) persistentMapOf() else persistentMapOf("image" to MediaSource("mxc://example.org/$label")),
        ))
    }

    private fun document(label: String) = DocumentNode(persistentListOf(ParagraphNode(AnnotatedString(label))))

    private fun assertAssets(state: InteractiveUiViewState, label: String) {
        assertThat(state.title).isEqualTo(label)
        val children = (state.root as InteractiveUiRenderNode.Stack).children
        val richText = children.filterIsInstance<InteractiveUiRenderNode.RichText>().single()
        assertThat(richText.text).isEqualTo(label)
        assertThat(richText.document).isEqualTo(document(label))
        val image = children.filterIsInstance<InteractiveUiRenderNode.Image>().single()
        assertThat(image.alt).isEqualTo(label)
        assertThat(image.source).isEqualTo(MediaSource("mxc://example.org/$label"))
    }

    private fun assertAction(content: JsonObject, revision: Long, eventId: EventId, senderId: UserId, threadRootId: ThreadId) {
        val action = content.getValue(InteractiveUiConstants.ACTION_KEY).jsonObject
        assertThat(action.getValue("revision").jsonPrimitive.content).isEqualTo(revision.toString())
        assertThat(action.getValue("ui_event_id").jsonPrimitive.content).isEqualTo(eventId.value)
        assertThat(action.getValue("values").jsonObject.getValue("name").jsonPrimitive.content).isEqualTo("Draft")
        assertThat(content.getValue("m.mentions").jsonObject.toString()).contains(senderId.value)
        val relation = content.getValue("m.relates_to").jsonObject
        assertThat(relation.getValue("event_id").jsonPrimitive.content).isEqualTo(threadRootId.value)
        assertThat(relation.getValue("m.in_reply_to").jsonObject.getValue("event_id").jsonPrimitive.content).isEqualTo(eventId.value)
    }
}
