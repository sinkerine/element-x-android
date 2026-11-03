/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.messages.impl.threads.mymessages

import android.view.View
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.element.android.features.messages.impl.timeline.TimelineEvent
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.tests.testutils.EventsRecorder
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.setSafeContent
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadMyMessagesPanelTest : RobolectricTest() {
    @Test
    fun `own avatar button exposes entry label and a 48dp touch target`() = runAndroidComposeUiTest {
        var clicks = 0
        setSafeContent { ThreadMyMessagesButton(aThreadMyMessagesState().ownAvatar, { clicks++ }) }
        onNodeWithContentDescription("Your messages in this thread")
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun `open panel preserves synthetic side navigation insets above it`() = runAndroidComposeUiTest {
        lateinit var view: View
        val state = mutableStateOf(aThreadMyMessagesState().copy(isOpen = false))
        setSafeContent {
            view = LocalView.current
            ThreadMyMessagesLayout(state.value, {}, Modifier.fillMaxSize()) {
                // Mirror MessagesView system bars and TimelineView horizontal safe drawing protection.
                Box(Modifier.fillMaxSize().systemBarsPadding()) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).testTag("safeTimeline"))
                }
            }
        }
        runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(
                view,
                WindowInsetsCompat.Builder()
                    .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(48, 0, 32, 24))
                    .build()
            )
        }
        waitForIdle()
        val closedBounds = onNodeWithTag("safeTimeline").fetchSemanticsNode().boundsInRoot
        assertEquals(48f, closedBounds.left, 1f)
        runOnIdle { state.value = state.value.copy(isOpen = true) }
        waitForIdle()
        val openBounds = onNodeWithTag("safeTimeline").fetchSemanticsNode().boundsInRoot
        assertEquals("Left safe inset must survive opening", closedBounds.left, openBounds.left, 1f)
        assertEquals("Right safe inset must survive opening", closedBounds.right, openBounds.right, 1f)
    }

    @Test
    fun `short available height still leaves timeline interactive`() = runAndroidComposeUiTest {
        var clicks = 0
        setSafeContent {
            ThreadMyMessagesLayout(aThreadMyMessagesState(), {}, Modifier.height(300.dp)) {
                io.element.android.libraries.designsystem.theme.components.TextButton(text = "Timeline event", onClick = { clicks++ })
            }
        }
        onNodeWithText("Timeline event").assertIsDisplayed().performClick()
        onNodeWithContentDescription("Close").assertIsDisplayed()
        runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun `rows jump without closing and expose full time root and current semantics`() = runAndroidComposeUiTest {
        val events = EventsRecorder<ThreadMyMessagesEvent>()
        val state = aThreadMyMessagesState().copy(currentEventId = EventId("$" + "root"), eventSink = events)
        setSafeContent { ThreadMyMessagesPanel(state, Modifier.height(360.dp)) }
        onNodeWithContentDescription("Today at 09:15, thread root, Plans for this week").assertIsSelected().performClick()
        events.assertSingle(ThreadMyMessagesEvent.JumpTo(EventId("$" + "root")))
        onNodeWithText("Your messages").assertIsDisplayed()
        onNodeWithContentDescription("Today at 10:42, I can help with that.").assertExists()
        onNodeWithText(" · 2+", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `close and retry dispatch actions while error keeps rows usable`() = runAndroidComposeUiTest {
        val events = EventsRecorder<ThreadMyMessagesEvent>()
        setSafeContent {
            ThreadMyMessagesPanel(aThreadMyMessagesState().copy(loadState = ThreadMyMessagesLoadState.Failed, eventSink = events), Modifier.height(360.dp))
        }
        onNodeWithText("Couldn’t load older messages").assertIsDisplayed()
        onNodeWithText("Retry").performClick()
        events.assertSingle(ThreadMyMessagesEvent.Retry)
        events.clear()
        onNodeWithContentDescription("Close").performClick()
        events.assertSingle(ThreadMyMessagesEvent.Close)
    }

    @Test
    fun `footer distinguishes loading complete empty failure and capped history`() = runAndroidComposeUiTest {
        val state = mutableStateOf(aThreadMyMessagesState())
        setSafeContent { ThreadMyMessagesPanel(state.value, Modifier.height(360.dp)) }
        onNodeWithText("Loading older messages…").assertIsDisplayed()
        onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertExists()
        runOnIdle { state.value = state.value.copy(isComplete = true, loadState = ThreadMyMessagesLoadState.Idle) }
        onNodeWithText("Start of thread").assertIsDisplayed()
        onNodeWithText(" · 2", useUnmergedTree = true).assertExists()
        onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertDoesNotExist()
        runOnIdle { state.value = state.value.copy(items = persistentListOf()) }
        onNodeWithText("You haven’t posted in this thread yet").assertIsDisplayed()
        onNodeWithText("Start of thread").assertIsDisplayed()
        runOnIdle { state.value = state.value.copy(isComplete = false, loadState = ThreadMyMessagesLoadState.Loading) }
        onNodeWithText("Loading older messages…").assertIsDisplayed()
        runOnIdle { state.value = state.value.copy(isLimitReached = true, loadState = ThreadMyMessagesLoadState.Idle) }
        onNodeWithText("Loading limit reached").assertIsDisplayed()
        onNodeWithText(" · 0+", useUnmergedTree = true).assertExists()
        onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun `downward handle drag closes panel`() = runAndroidComposeUiTest {
        val events = EventsRecorder<ThreadMyMessagesEvent>()
        setSafeContent { ThreadMyMessagesPanel(aThreadMyMessagesState().copy(eventSink = events), Modifier.height(360.dp)) }
        onNodeWithText("Your messages").performTouchInput {
            // Start on the handle's full-width touch area, immediately above the header.
            down(center.copy(y = -24.dp.toPx()))
            moveBy(androidx.compose.ui.geometry.Offset(0f, 60.dp.toPx()))
            up()
        }
        events.assertSingle(ThreadMyMessagesEvent.Close)
    }

    @Test
    fun `layout delivers loaded jump once keeps panel open and back closes it`() = runAndroidComposeUiTest {
        val events = EventsRecorder<ThreadMyMessagesEvent>()
        val timelineEvents = EventsRecorder<TimelineEvent>()
        val state = mutableStateOf(aThreadMyMessagesState().copy(pendingJump = EventId("$" + "reply"), eventSink = events))
        setSafeContent {
            ThreadMyMessagesLayout(state.value, timelineEvents, Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) }
        }
        timelineEvents.assertSingle(TimelineEvent.FocusOnEvent(EventId("$" + "reply")))
        events.assertSingle(ThreadMyMessagesEvent.JumpConsumed)
        onNodeWithText("Your messages").assertIsDisplayed()
        events.clear()
        runOnIdle { state.value = state.value.copy(pendingJump = null) }
        runOnIdle {
            (activity as ComponentActivity).onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            (activity as ComponentActivity).onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(20f, 0f, 0.5f, BackEventCompat.EDGE_LEFT))
            (activity as ComponentActivity).onBackPressedDispatcher.dispatchOnBackCancelled()
        }
        waitForIdle()
        events.assertEmpty()
        runOnIdle {
            (activity as ComponentActivity).onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            (activity as ComponentActivity).onBackPressedDispatcher.onBackPressed()
        }
        waitForIdle()
        events.assertSingle(ThreadMyMessagesEvent.Close)
    }

    @Test
    fun `announcements are delivered and consumed including identical later text`() = runAndroidComposeUiTest {
        val events = mutableListOf<ThreadMyMessagesEvent>()
        val state = mutableStateOf(aThreadMyMessagesState())
        val announcement = "Your messages in this thread, 2+"
        setSafeContent {
            ThreadMyMessagesLayout(state.value, {}, Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) }
        }
        repeat(2) {
            runOnIdle {
                state.value = state.value.copy(announcement = announcement, eventSink = {
                    events.add(it)
                    if (it == ThreadMyMessagesEvent.AnnouncementConsumed) state.value = state.value.copy(announcement = null)
                })
            }
            waitForIdle()
            onNodeWithContentDescription(announcement).assertExists()
            runOnIdle { assertEquals(it + 1, events.count { event -> event == ThreadMyMessagesEvent.AnnouncementConsumed }) }
        }
    }
}
