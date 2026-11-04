/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.messages.impl.timeline.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertThat
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.messages.impl.interactiveui.InMemoryInteractiveUiStateStore
import io.element.android.features.messages.impl.interactiveui.InteractiveUiPresenter
import io.element.android.features.messages.impl.interactiveui.InteractiveUiSessionRegistry
import io.element.android.features.messages.impl.interactiveui.interactiveUiContent
import io.element.android.features.messages.impl.timeline.TimelineView
import io.element.android.features.messages.impl.timeline.aTimelineItemEvent
import io.element.android.features.messages.impl.timeline.aTimelineItemReadReceipts
import io.element.android.features.messages.impl.timeline.aTimelineRoomInfo
import io.element.android.features.messages.impl.timeline.aTimelineState
import io.element.android.features.messages.impl.timeline.components.receipt.aReadReceiptData
import io.element.android.features.messages.impl.timeline.di.LocalTimelineItemPresenterFactories
import io.element.android.features.messages.impl.timeline.di.TimelineItemPresenterFactories
import io.element.android.features.messages.impl.timeline.di.TimelineItemPresenterFactory
import io.element.android.features.messages.impl.timeline.model.TimelineItemGroupPosition
import io.element.android.features.messages.impl.timeline.model.event.InteractiveUiTimelineModel
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemInteractiveUiContent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemTextContent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemUnknownContent
import io.element.android.features.messages.impl.timeline.protection.aTimelineProtectionState
import io.element.android.libraries.designsystem.utils.LocalUiTestMode
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiConstants
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiParseResult
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiParser
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiStatus
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiValue
import io.element.android.libraries.interactiveui.core.session.InteractiveUiPhase
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiViewState
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.setSafeContent
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-560dpi")
class TimelineItemInteractiveUiStatusLayoutTest : RobolectricTest() {
    @Test
    fun `sender edit preserves keyed row geometry in actual timeline at bottom`() = verifyStatusEdit(0, 0)

    @Test
    fun `sender edit preserves keyed row geometry in actual timeline while scrolled`() = verifyStatusEdit(0, 160)

    @Test
    fun `receipt moves from card to action reply without shifting scrolled timeline`() = verifyStatusEdit(0, 160, removeReceipt = true)

    @Test
    @Config(qualifiers = "w411dp-h891dp-mdpi")
    fun `receipt moves to action reply at standard density without shifting card`() = verifyStatusEdit(0, 160, removeReceipt = true)

    @Test
    fun `receipt moves to action reply at large font without shifting card`() = verifyStatusEdit(0, 160, removeReceipt = true, fontScale = 2f)

    @Test
    fun `receipt appears without shifting scrolled timeline`() = verifyStatusEdit(0, 160, addReceipt = true)

    @Test
    @Config(qualifiers = "w411dp-h891dp-mdpi")
    fun `ordinary text retains its existing receipt height behavior`() = runAndroidComposeUiTest {
        val fixture = Fixture().apply { ordinaryText = true }
        setTimeline(fixture)
        val before = fixture.listState.layoutInfo.visibleItemsInfo.single { it.key == fixture.row.identifier() }.size
        runOnIdle { fixture.hasReceipt.value = true }
        waitForIdle()
        val after = fixture.listState.layoutInfo.visibleItemsInfo.single { it.key == fixture.row.identifier() }.size
        assertThat(after - before).isEqualTo(28)
    }

    @Test
    fun `overdue retry removal still changes card height independently of receipts`() = verifyStatusEdit(0, 160, removeReceipt = true, overdue = true)

    private fun verifyStatusEdit(
        initialIndex: Int,
        initialOffset: Int,
        removeReceipt: Boolean = false,
        addReceipt: Boolean = false,
        fontScale: Float = 1f,
        overdue: Boolean = false,
    ) = runAndroidComposeUiTest {
        val fixture = Fixture()
        fixture.hasReceipt.value = removeReceipt
        fixture.fontScale = fontScale
        val json = requireNotNull(javaClass.getResource("/interactive-ui-controls.json")).readText()
            .replace(
                "\"include\": [\"plan\", \"features\", \"name\", \"quantity\"]",
                "\"single_use\": true, \"include\": [\"plan\", \"features\", \"name\", \"quantity\"]"
            )
        val parsed = InteractiveUiParser.parse(
            buildJsonObject {
                put(
                    InteractiveUiConstants.PAYLOAD_KEY,
                    Json.parseToJsonElement(json)
                )
            }
        ) as InteractiveUiParseResult.Valid
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val room = object : JoinedRoom by FakeJoinedRoom(
                roomCoroutineScope = scope
            ) {
            override suspend fun sendRaw(eventType: String, content: String) = Result.success(
                EventId("$" + "action")
            )
        }
        val registry = InteractiveUiSessionRegistry(
            room,
            InMemoryInteractiveUiStateStore()
        )
        fixture.registry = registry
        val knowledgeJson = requireNotNull(javaClass.getResource("/interactive-ui-knowledge-map.json")).readText()
        val knowledge = InteractiveUiParser.parse(buildJsonObject {
            put(InteractiveUiConstants.PAYLOAD_KEY, Json.parseToJsonElement(knowledgeJson))
        }) as InteractiveUiParseResult.Valid
        fixture.knowledge.value = interactiveUiContent().copy(
            eventId = EventId("\$knowledge"),
            model = InteractiveUiTimelineModel.Valid(knowledge.payload, persistentMapOf(), persistentMapOf())
        )
        fixture.content.value = interactiveUiContent().copy(
            model = InteractiveUiTimelineModel.Valid(
                parsed.payload,
                persistentMapOf(),
                persistentMapOf()
            )
        )
        val entry = registry.sessionFor(fixture.content.value)
        entry.session.setValue("text_input", InteractiveUiValue.Text("Alice"))
        setTimeline(fixture)
        runOnIdle { runBlocking { fixture.listState.scrollToItem(initialIndex + 1, initialOffset) } }
        waitForIdle()
        runOnIdle { entry.session.activate("submit") }
        waitUntil(timeoutMillis = 10_000) { entry.state.value.sessionState.phase == InteractiveUiPhase.AwaitingConfirmation }
        if (overdue) waitUntil(timeoutMillis = 12_000) { entry.state.value.sessionState.confirmationOverdue }
        waitForIdle()
        val before = fixture.snapshot()
        val rowBefore = fixture.listState.layoutInfo.visibleItemsInfo.single { it.key == fixture.row.identifier() }
        val statusBefore = onNodeWithText(
            if (overdue) "No confirmation from ${fixture.content.value.senderDisplayName} yet" else "Waiting for confirmation…",
            useUnmergedTree = true
        ).getUnclippedBoundsInRoot()
        runOnIdle {
            fixture.hasReceipt.value = addReceipt
            fixture.replyHasReceipt.value = removeReceipt
            fixture.content.value = fixture.content.value.copy(
                model = InteractiveUiTimelineModel.Valid(
                    parsed.payload.copy(
                        revision = 1,
                        status = InteractiveUiStatus.Submitted,
                        statusText = "Your choice was accepted"
                    ),
                    persistentMapOf(),
                    persistentMapOf()
                ),
                fallback = fixture.content.value.fallback.copy(isEdited = true)
            )
        }
        waitForIdle()
        val after = fixture.snapshot()
        val rowAfter = fixture.listState.layoutInfo.visibleItemsInfo.single { it.key == fixture.row.identifier() }
        val statusAfter = onNodeWithText("Your choice was accepted", useUnmergedTree = true).getUnclippedBoundsInRoot()
        println("STATUS_GEOMETRY before=$before after=$after statusBefore=$statusBefore statusAfter=$statusAfter")
        if (overdue) {
            assertThat(rowAfter.size).isLessThan(rowBefore.size)
        } else {
            assertThat(rowAfter.size).isEqualTo(rowBefore.size)
            assertThat(after).isEqualTo(before)
            assertThat(statusAfter.top.value).isWithin(2f).of(statusBefore.top.value)
        }
        assertThat(rowAfter.offset).isEqualTo(rowBefore.offset)
        assertThat((statusAfter.bottom - statusAfter.top).value).isWithin(1f).of((statusBefore.bottom - statusBefore.top).value)
        scope.cancel()
    }

    private class Fixture {
        var ordinaryText = false
        var fontScale = 1f
        lateinit var registry: InteractiveUiSessionRegistry
        val content = mutableStateOf(interactiveUiContent())
        val hasReceipt = mutableStateOf(false)
        val replyHasReceipt = mutableStateOf(false)
        val reply = aTimelineItemEvent(isMine = true, content = aTimelineItemUnknownContent())
        val row = aTimelineItemEvent(
            content = content.value,
            canBeRepliedTo = true,
            groupPosition = TimelineItemGroupPosition.Last,
        )
        val knowledge = mutableStateOf(interactiveUiContent())
        val knowledgeRow = aTimelineItemEvent(content = knowledge.value)
        val olderRows = List(4) { index ->
            knowledgeRow.copy(id = UniqueId("older-$index"), eventId = EventId("\$older-$index"))
        }
        val listState = LazyListState()

        fun snapshot(): String = with(listState) {
            "index=$firstVisibleItemIndex offset=$firstVisibleItemScrollOffset " +
                "viewport=${layoutInfo.viewportStartOffset}:${layoutInfo.viewportEndOffset} " +
                "items=" + layoutInfo.visibleItemsInfo.joinToString { "${it.key}:${it.offset}:${it.size}" }
        }
    }

    private fun AndroidComposeUiTest<ComponentActivity>.setTimeline(fixture: Fixture) {
        val factories = TimelineItemPresenterFactories(mapOf(
            TimelineItemInteractiveUiContent::class to
                TimelineItemPresenterFactory<TimelineItemInteractiveUiContent, InteractiveUiViewState> { content ->
                    InteractiveUiPresenter(content, fixture.registry)
                }
        ))
        setSafeContent {
            ElementTheme {
                CompositionLocalProvider(
                    LocalTimelineItemPresenterFactories provides factories,
                    LocalUiTestMode provides fixture.ordinaryText,
                    LocalDensity provides Density(LocalDensity.current.density, fixture.fontScale),
                ) {
                    TimelineView(
                        state = aTimelineState(
                            timelineItems = (listOf(
                                fixture.reply.copy(
                                    readReceiptState = aTimelineItemReadReceipts(
                                        if (fixture.replyHasReceipt.value) listOf(aReadReceiptData(0)) else emptyList()
                                    )
                                ),
                                fixture.row.copy(
                                    content = if (fixture.ordinaryText) aTimelineItemTextContent() else fixture.content.value,
                                    readReceiptState = aTimelineItemReadReceipts(if (fixture.hasReceipt.value) listOf(aReadReceiptData(0)) else emptyList()),
                                ),
                                fixture.knowledgeRow.copy(content = if (fixture.ordinaryText) aTimelineItemUnknownContent() else fixture.knowledge.value),
                            ) + fixture.olderRows.map {
                                it.copy(
                                    content = if (fixture.ordinaryText) aTimelineItemUnknownContent() else fixture.knowledge.value.copy(eventId = it.eventId!!)
                                )
                            }).toImmutableList(),
                            timelineRoomInfo = aTimelineRoomInfo(isDm = false),
                            useNewTimelineEventRenderer = true,
                        ),
                        lazyListState = fixture.listState,
                        timelineProtectionState = aTimelineProtectionState(),
                        onUserDataClick = {},
                        onLinkClick = {},
                        onContentClick = {},
                        onGalleryItemClick = { _, _ -> },
                        onMessageLongClick = {},
                        onSwipeToReply = {},
                        onReactionClick = { _, _ -> },
                        onReactionLongClick = { _, _ -> },
                        onMoreReactionsClick = {},
                        onReadReceiptClick = {},
                        onJoinCallClick = {},
                    )
                }
            }
        }
        waitForIdle()
    }
}
