/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.messages.impl.timeline

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.messages.impl.threads.mymessages.ThreadMyMessagesLayout
import io.element.android.features.messages.impl.threads.mymessages.aThreadMyMessagesState
import io.element.android.features.messages.impl.timeline.components.MessageShieldData
import io.element.android.features.messages.impl.timeline.components.aCriticalShield
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemTextContent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemUnknownContent
import io.element.android.features.messages.impl.timeline.model.virtual.TimelineItemLoadingIndicatorModel
import io.element.android.features.messages.impl.timeline.protection.TimelineProtectionState
import io.element.android.features.messages.impl.timeline.protection.aTimelineProtectionState
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.MessageShield
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.A_USER_ID
import io.element.android.libraries.matrix.ui.messages.reply.InReplyToDetails
import io.element.android.libraries.matrix.ui.messages.reply.aProfileDetailsReady
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.tests.testutils.EnsureNeverCalledWithParam
import io.element.android.tests.testutils.EnsureNeverCalledWithTwoParams
import io.element.android.tests.testutils.EventsRecorder
import io.element.android.tests.testutils.clickOn
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.setSafeContent
import io.element.android.wysiwyg.link.Link
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.robolectric.annotation.GraphicsMode

class TimelineViewTest : RobolectricTest() {
    @Test
    fun `visible ids follow reverse list order include partial events skip virtual rows and avoid duplicates`() = runAndroidComposeUiTest {
        val listState = LazyListState()
        val reports = mutableListOf<ImmutableList<EventId>>()
        val events = (0..30).map { aTimelineItemEvent(eventId = EventId("$" + "visible$it"), content = aTimelineItemUnknownContent()) }
        val items = (events.take(2) + TimelineItem.Virtual(
            UniqueId("virtual"),
            TimelineItemLoadingIndicatorModel(Timeline.PaginationDirection.BACKWARDS, 0)
        ) + events.drop(2)).toImmutableList()
        setTimelineView(
            state = aTimelineState(timelineItems = items, isLive = false),
            lazyListState = listState,
            onVisibleEventIdsChange = { reports.add(it) },
            layout = { content ->
                androidx.compose.foundation.layout.Box(
                Modifier.height(177.dp)
            ) { content(aTimelineState(timelineItems = items, isLive = false)) }
            }
        )
        fun assertVisible() {
            runOnIdle {
                val layout = listState.layoutInfo
                val expected = layout.visibleItemsInfo.sortedBy { it.index }.mapNotNull { (items[it.index] as? TimelineItem.Event)?.eventId }
                assertTrue(expected.isNotEmpty())
                assertEquals(expected, reports.last())
                assertTrue(reports.zipWithNext().none { (a, b) -> a == b })
            }
        }
        assertVisible()
        onNodeWithTag("timeline").performScrollToIndex(5)
        assertVisible()
        val count = runOnIdle { reports.size }
        mainClock.advanceTimeBy(200)
        runOnIdle { assertEquals(count, reports.size) }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun `outlined event draws accent border and clears without changing focus`() = runAndroidComposeUiTest {
        val target = aTimelineItemEvent(eventId = EventId("$" + "outline"), content = aTimelineItemUnknownContent())
        val outline = mutableStateOf<EventId?>(target.eventId)
        var accent = Color.Transparent
        setSafeContent {
            accent = ElementTheme.colors.borderAccentSubtle
            TimelineView(
                state = aTimelineState(timelineItems = persistentListOf(target), isLive = false),
                timelineProtectionState = aTimelineProtectionState(),
                outlinedEventId = outline.value,
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
                modifier = Modifier.height(200.dp),
            )
        }
        fun accentPixels(): Int {
            val pixels = onNodeWithTag("timeline").captureToImage().toPixelMap()
            return (0 until pixels.height).count { pixels[1, it] == accent }
        }
        assertTrue("Outline must paint an accent border", accentPixels() > 10)
        runOnIdle { outline.value = null }
        assertEquals(0, accentPixels())
    }

    @Test
    fun `thread focus centers highlighted target in viewport reduced by open panel`() = runAndroidComposeUiTest {
        val listState = LazyListState()
        val panelState = mutableStateOf(aThreadMyMessagesState().copy(isOpen = false))
        val targetIndex = 30
        val items = List(60) {
            aTimelineItemEvent(eventId = EventId("\$event_$it"), content = aTimelineItemTextContent(body = "Message $it"))
        }.toImmutableList()
        var rendered = false
        val state = mutableStateOf(
            aTimelineState(
                timelineItems = items,
                timelineMode = Timeline.Mode.Thread(ThreadId("\$root")),
                isLive = false,
                eventSink = { if (it == TimelineEvent.OnFocusEventRender) rendered = true },
            )
        )
        setTimelineView(
            state = state.value,
            lazyListState = listState,
            layout = { content ->
                ThreadMyMessagesLayout(panelState.value, {}, Modifier.height(600.dp)) { content(state.value) }
            },
        )
        val fullHeight = runOnIdle { listState.layoutInfo.viewportSize.height }
        runOnIdle { panelState.value = panelState.value.copy(isOpen = true) }
        runOnIdle {
            state.value = state.value.copy(
                focusRequestState = FocusRequestState.Success(items[targetIndex].eventId!!, targetIndex),
            )
        }
        waitUntil(timeoutMillis = 5_000) { rendered }
        runOnIdle { state.value = state.value.copy(focusRequestState = (state.value.focusRequestState as FocusRequestState.Success).copy(rendered = true)) }
        waitForIdle()
        val highlightedSize = runOnIdle {
            val info = listState.layoutInfo
            assertTrue("Panel must shrink the timeline", info.viewportSize.height < fullHeight)
            assertTrue("Focus must complete rendering", rendered)
            val target = info.visibleItemsInfo.single { it.index == targetIndex }
            val usableCenter = (info.viewportEndOffset + info.viewportStartOffset + info.beforeContentPadding - info.afterContentPadding) / 2f
            assertEquals("Target must be centered in the reduced viewport", usableCenter, target.offset + target.size / 2f, 1f)
            target.size
        }
        runOnIdle { state.value = state.value.copy(focusRequestState = FocusRequestState.None) }
        waitForIdle()
        runOnIdle {
            val target = listState.layoutInfo.visibleItemsInfo.single { it.index == targetIndex }
            assertEquals(
                "Focus styling remains after rendering, adding its 4dp highlight padding",
                with(density) { 4.dp.roundToPx() },
                highlightedSize - target.size
            )
        }
    }

    @Test
    fun `default timeline has no own messages entry`() = runAndroidComposeUiTest {
        setTimelineView(state = aTimelineState())
        onNodeWithContentDescription("Your messages in this thread").assertDoesNotExist()
    }

    @Test
    fun `floating slot is optional and renders alongside existing jump control`() = runAndroidComposeUiTest {
        setTimelineView(
            state = aTimelineState(),
            forceJumpToBottomVisibility = true,
            floatingActions = { Text("Own messages action") },
        )
        onNodeWithText("Own messages action").assertIsDisplayed()
        onNodeWithContentDescription(activity!!.getString(CommonStrings.a11y_jump_to_bottom)).assertIsDisplayed()
    }

    @Test
    fun `reaching the end of the timeline with more events to load emits a LoadMore event`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<TimelineEvent>()
        setTimelineView(
            state = aTimelineState(
                timelineItems = persistentListOf<TimelineItem>(
                    TimelineItem.Virtual(
                        id = UniqueId("backward_pagination"),
                        model = TimelineItemLoadingIndicatorModel(Timeline.PaginationDirection.BACKWARDS, 0)
                    ),
                ),
                eventSink = eventsRecorder,
            ),
        )
        eventsRecorder.assertSingle(TimelineEvent.LoadMore(Timeline.PaginationDirection.BACKWARDS))
    }

    @Test
    fun `reaching the end of the timeline does not send a LoadMore event`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<TimelineEvent>()
        setTimelineView(
            state = aTimelineState(
                timelineItems = persistentListOf(aTimelineItemEvent(content = aTimelineItemTextContent())),
                eventSink = eventsRecorder,
            ),
        )
        eventsRecorder.assertSingle(TimelineEvent.OnScrollFinished(firstIndex = 0))
    }

    @Test
    fun `scroll to bottom on live timeline does not emit the Event`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<TimelineEvent>()
        setTimelineView(
            state = aTimelineState(
                timelineItems = persistentListOf(aTimelineItemEvent(content = aTimelineItemTextContent())),
                isLive = true,
                eventSink = eventsRecorder,
            ),
            forceJumpToBottomVisibility = true,
        )

        eventsRecorder.assertSingle(TimelineEvent.OnScrollFinished(firstIndex = 0))
        eventsRecorder.clear()

        val contentDescription = activity!!.getString(CommonStrings.a11y_jump_to_bottom)
        onNodeWithContentDescription(contentDescription).performClick()
    }

    @Test
    fun `scroll to bottom on detached timeline emits the expected Event`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<TimelineEvent>()
        setTimelineView(
            state = aTimelineState(
                timelineItems = persistentListOf(aTimelineItemEvent(content = aTimelineItemTextContent())),
                isLive = false,
                eventSink = eventsRecorder,
            ),
        )

        eventsRecorder.assertSingle(TimelineEvent.OnScrollFinished(firstIndex = 0))
        eventsRecorder.clear()

        val contentDescription = activity!!.getString(CommonStrings.a11y_jump_to_bottom)
        onNodeWithContentDescription(contentDescription).performClick()
        eventsRecorder.assertSingle(TimelineEvent.JumpToLive)
    }

    @Test
    fun `an empty timeline triggers a prefetch`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<TimelineEvent>()
        setTimelineView(
            state = aTimelineState(
                timelineItems = persistentListOf(),
                eventSink = eventsRecorder,
            ),
        )

        eventsRecorder.assertSingle(TimelineEvent.LoadMore(Timeline.PaginationDirection.BACKWARDS))
    }

    @Test
    fun `show shield dialog`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<TimelineEvent>()
        setTimelineView(
            state = aTimelineState(
                timelineItems = persistentListOf<TimelineItem>(
                    aTimelineItemEvent(
                        // Do not use a Text because EditorStyledText cannot be used in UI test.
                        content = aTimelineItemTextContent(),
                        messageShield = MessageShield.UnverifiedIdentity(true),
                    ),
                ),
                eventSink = eventsRecorder,
            ),
        )
        val contentDescription = activity!!.getString(CommonStrings.a11y_encryption_details)
        onNodeWithContentDescription(contentDescription).performClick()
        eventsRecorder.assertList(
            listOf(
                TimelineEvent.OnScrollFinished(0),
                TimelineEvent.ShowShieldDialog(MessageShieldData(MessageShield.UnverifiedIdentity(true))),
            )
        )
    }

    @Test
    fun `hide shield dialog`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<TimelineEvent>()
        setTimelineView(
            state = aTimelineState(
                timelineItems = persistentListOf(aTimelineItemEvent(content = aTimelineItemTextContent())),
                isLive = false,
                eventSink = eventsRecorder,
                messageShield = aCriticalShield(),
            ),
        )
        eventsRecorder.assertSingle(TimelineEvent.OnScrollFinished(firstIndex = 0))
        eventsRecorder.clear()

        clickOn(CommonStrings.action_ok)
        eventsRecorder.assertSingle(TimelineEvent.HideShieldDialog)
    }

    @Test
    fun `clicking on the send failure indicator emits the expected Event`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<TimelineEvent>()
        val timelineItem = aTimelineItemEvent(
            content = aTimelineItemTextContent(),
            sendState = LocalEventSendState.Failed.Unknown("Error"),
        )
        setTimelineView(
            state = aTimelineState(
                timelineItems = persistentListOf(timelineItem),
                eventSink = eventsRecorder,
            ),
        )
        eventsRecorder.clear()

        val contentDescription = activity!!.getString(CommonStrings.common_sending_failed)
        onNodeWithContentDescription(contentDescription).performClick()
        eventsRecorder.assertSingle(TimelineEvent.ShowSendFailureDialog(timelineItem))
    }

    @Test
    fun `clicking on the send failure indicator of a verified user failure emits the expected Event`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<TimelineEvent>()
        val timelineItem = aTimelineItemEvent(
            content = aTimelineItemTextContent(),
            sendState = LocalEventSendState.Failed.VerifiedUserChangedIdentity(users = listOf(A_USER_ID)),
        )
        setTimelineView(
            state = aTimelineState(
                timelineItems = persistentListOf(timelineItem),
                eventSink = eventsRecorder,
            ),
        )
        eventsRecorder.clear()

        val contentDescription = activity!!.getString(CommonStrings.common_sending_failed)
        onNodeWithContentDescription(contentDescription).performClick()
        eventsRecorder.assertSingle(TimelineEvent.ShowSendFailureDialog(timelineItem))
    }

    @Test
    fun `clicking on a reply to a loaded event emits the expected Event`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<TimelineEvent>()
        setTimelineView(
            state = aTimelineState(
                timelineItems = persistentListOf(
                    aTimelineItemEvent(
                        content = aTimelineItemTextContent(),
                        inReplyTo = InReplyToDetails.Ready(
                            eventId = AN_EVENT_ID,
                            senderId = A_USER_ID,
                            senderProfile = aProfileDetailsReady(),
                            eventContent = MessageContent(
                                body = "Replied message",
                                inReplyTo = null,
                                isEdited = false,
                                threadInfo = null,
                                type = TextMessageType("Replied message", null),
                            ),
                            textContent = "Replied message",
                        ),
                    ),
                ),
                eventSink = eventsRecorder,
            ),
        )
        eventsRecorder.clear()

        onAllNodesWithText("Replied message").onFirst().performClick()
        eventsRecorder.assertSingle(TimelineEvent.FocusOnEvent(AN_EVENT_ID))
    }

    @Test
    fun `a reply to an event which could not be loaded renders a placeholder and does not emit any Event when clicked`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<TimelineEvent>()
        setTimelineView(
            state = aTimelineState(
                timelineItems = persistentListOf(
                    aTimelineItemEvent(
                        content = aTimelineItemTextContent(),
                        inReplyTo = InReplyToDetails.Error(eventId = AN_EVENT_ID, message = "Failed to fetch the event"),
                    ),
                ),
                eventSink = eventsRecorder,
            ),
            onMessageClick = {},
        )
        eventsRecorder.assertSingle(TimelineEvent.OnScrollFinished(firstIndex = 0))
        eventsRecorder.clear()

        onNodeWithText("Failed to fetch the event").assertDoesNotExist()
        onNodeWithText(activity!!.getString(CommonStrings.error_message_not_found)).assertIsDisplayed().performClick()
        eventsRecorder.assertEmpty()
    }

    @Ignore(
        "performScrollToIndex in compose tests no longer sets LazyListState.isScrollInProgress to true, so the LoadMore event is not emitted." +
            "This needs to be reworked to use a different approach to check the LoadMore event was emitted."
    )
    @Test
    fun `scrolling near to the start of the loaded items triggers a pre-fetch`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<TimelineEvent>()
        val items = List<TimelineItem>(200) {
            aTimelineItemEvent(
                eventId = EventId("\$event_$it"),
                content = aTimelineItemUnknownContent(),
            )
        }.toImmutableList()

        setTimelineView(
            state = aTimelineState(
                timelineItems = items,
                eventSink = eventsRecorder,
                focusedEventIndex = -1,
                isLive = false,
            ),
        )

        onNodeWithTag("timeline").performScrollToIndex(180)

        mainClock.advanceTimeBy(1000)

        eventsRecorder.assertList(
            listOf(
                TimelineEvent.OnScrollFinished(firstIndex = 0),
                TimelineEvent.LoadMore(Timeline.PaginationDirection.BACKWARDS),
            )
        )
    }
}

private fun AndroidComposeUiTest<ComponentActivity>.setTimelineView(
    state: TimelineState,
    timelineProtectionState: TimelineProtectionState = aTimelineProtectionState(),
    onUserDataClick: (MatrixUser) -> Unit = EnsureNeverCalledWithParam(),
    onLinkClick: (Link) -> Unit = EnsureNeverCalledWithParam(),
    onMessageClick: (TimelineItem.Event) -> Unit = EnsureNeverCalledWithParam(),
    onMessageLongClick: (TimelineItem.Event) -> Unit = EnsureNeverCalledWithParam(),
    onSwipeToReply: (TimelineItem.Event) -> Unit = EnsureNeverCalledWithParam(),
    onReactionClick: (emoji: String, TimelineItem.Event) -> Unit = EnsureNeverCalledWithTwoParams(),
    onReactionLongClick: (emoji: String, TimelineItem.Event) -> Unit = EnsureNeverCalledWithTwoParams(),
    onMoreReactionsClick: (TimelineItem.Event) -> Unit = EnsureNeverCalledWithParam(),
    onReadReceiptClick: (TimelineItem.Event) -> Unit = EnsureNeverCalledWithParam(),
    onGalleryItemClick: (TimelineItem.Event, Int) -> Unit = EnsureNeverCalledWithTwoParams(),
    forceJumpToBottomVisibility: Boolean = false,
    onJoinCallClick: (isAudioCall: Boolean) -> Unit = EnsureNeverCalledWithParam(),
    floatingActions: @Composable ColumnScope.() -> Unit = {},
    lazyListState: LazyListState? = null,
    onVisibleEventIdsChange: ((ImmutableList<EventId>) -> Unit)? = null,
    layout: @Composable (@Composable (TimelineState) -> Unit) -> Unit = { it(state) },
) {
    setSafeContent(clearAndroidUiDispatcher = true) {
        layout { currentState ->
            TimelineView(
                state = currentState,
                lazyListState = lazyListState ?: rememberLazyListState(),
                timelineProtectionState = timelineProtectionState,
                onUserDataClick = onUserDataClick,
                onLinkClick = onLinkClick,
                onContentClick = onMessageClick,
                onMessageLongClick = onMessageLongClick,
                onSwipeToReply = onSwipeToReply,
                onReactionClick = onReactionClick,
                onReactionLongClick = onReactionLongClick,
                onMoreReactionsClick = onMoreReactionsClick,
                onReadReceiptClick = onReadReceiptClick,
                onGalleryItemClick = onGalleryItemClick,
                forceJumpToBottomVisibility = forceJumpToBottomVisibility,
                onJoinCallClick = onJoinCallClick,
                floatingActions = floatingActions,
                onVisibleEventIdsChange = onVisibleEventIdsChange,
            )
        }
    }
}
