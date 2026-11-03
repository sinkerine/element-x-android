/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.threads.mymessages

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.TurbineTestContext
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.R
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.dateformatter.test.FakeDateFormatter
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarDispatcher
import io.element.android.libraries.eventformatter.test.FakePinnedMessagesBannerFormatter
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.PollContent
import io.element.android.libraries.matrix.api.timeline.item.event.getDisambiguatedDisplayName
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.timeline.aMessageContent
import io.element.android.libraries.matrix.test.timeline.aPollContent
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadMyMessagesPresenterTest : RobolectricTest() {
    @Test
    fun `empty thread hides entry until own remote message arrives and hides again when removed`() = runTest {
        val timeline = RecordingThreadTimeline(hasMore = false)
        presenter(timeline).testWithContext {
            runCurrent()
            var state = expectMostRecentItem()
            assertThat(state.isEntryVisible).isFalse()
            timeline.timelineItems.value = listOf(ownEvent(0))
            state = awaitState { it.items.size == 1 }
            assertThat(state.isEntryVisible).isTrue()
            timeline.timelineItems.value = emptyList()
            state = awaitState { it.items.isEmpty() }
            assertThat(state.isEntryVisible).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `edits update existing row and open panel remains reachable after its last own event disappears`() = runTest {
        val original = ownEvent(0)
        val timeline = RecordingThreadTimeline(hasMore = false).apply { timelineItems.value = listOf(original) }
        presenter(timeline).testWithContext {
            var state = awaitState { it.items.size == 1 }
            timeline.timelineItems.value = listOf(original.copy(event = original.event.copy(content = aMessageContent("edited", isEdited = true))))
            state = awaitState { it.items.singleOrNull()?.isEdited == true }
            assertThat(state.items.single().eventId).isEqualTo(original.eventId)
            assertThat(state.items.single().body).isEqualTo("edited")
            state.eventSink(ThreadMyMessagesEvent.Open)
            state = awaitState { it.isOpen && it.announcement != null }
            timeline.timelineItems.value = emptyList()
            state = awaitState { it.items.isEmpty() }
            assertThat(state.isEntryVisible).isTrue()
            assertThat(state.isOpen).isTrue()
            state.eventSink(ThreadMyMessagesEvent.Close)
            state = awaitState { !it.isOpen }
            assertThat(state.isEntryVisible).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `inbox entry is visible with no loaded own messages and profile supplies avatar`() = runTest {
        val client = FakeMatrixClient(userDisplayName = "My name", userAvatarUrl = "mxc://example/avatar")
        presenter(openedFromInbox = true, client = client).testWithContext {
            val state = awaitItem()
            assertThat(state.isEntryVisible).isTrue()
            assertThat(state.ownAvatar.id).isEqualTo(client.sessionId.value)
            assertThat(state.ownAvatar.name).isEqualTo("My name")
            assertThat(state.ownAvatar.url).isEqualTo("mxc://example/avatar")
            assertThat(state.ownAvatar.size).isEqualTo(AvatarSize.ThreadMyMessagesButton)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `root included newest first with latest edited content and exact formatted times`() = runTest {
        val timeline = RecordingThreadTimeline(hasMore = false).apply {
            timelineItems.value = listOf(
                ownEvent(0),
                ownEvent(1, aMessageContent("latest edit\nsecond line", isEdited = true)),
                ownEvent(2, aPollContent(isEdited = true)),
            )
        }
        presenter(timeline).testWithContext {
            val state = awaitState { it.items.size == 3 }
            assertThat(state.items.map { it.eventId }).containsExactly(ownEvent(2).eventId, ownEvent(1).eventId, ownEvent(0).eventId).inOrder()
            assertThat(state.items.map { it.isRoot }).containsExactly(false, false, true).inOrder()
            assertThat(state.items.map { it.isEdited }).containsExactly(true, true, false).inOrder()
            val edited = state.items[1]
            assertThat(edited.body).isEqualTo("latest edit\nsecond line")
            assertThat(edited.snippet).isEqualTo("latest edit")
            assertThat(edited.time).isEqualTo("1 TimeOrDate true")
            assertThat(edited.fullTime).isEqualTo("1 Full true")
            assertThat(state.isComplete).isTrue()
            assertThat(timeline.calls).isEqualTo(0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `open announces N plus and starts single flight loading while rows arrive progressively`() = runTest {
        val timeline = RecordingThreadTimeline().apply { timelineItems.value = listOf(ownEvent(0)) }
        val pageGate = CompletableDeferred<Unit>()
        timeline.page = {
            pageGate.await()
            timeline.timelineItems.value += ownEvent(1)
            timeline.backwardPaginationStatus.value = Timeline.PaginationStatus(false, false)
            Result.success(true)
        }
        presenter(timeline).testWithContext {
            var state = awaitState { it.items.size == 1 }
            state.eventSink(ThreadMyMessagesEvent.Open)
            state = awaitState { it.loadState == ThreadMyMessagesLoadState.Loading && it.announcement != null }
            assertThat(state.isOpen).isTrue()
            assertThat(state.announcement).isEqualTo("Your messages in this thread, 1+")
            assertThat(state.isComplete).isFalse()
            repeat(3) { state.eventSink(ThreadMyMessagesEvent.Open) }
            state.eventSink(ThreadMyMessagesEvent.Retry)
            runCurrent()
            assertThat(timeline.calls).isEqualTo(1)
            pageGate.complete(Unit)
            state = awaitState { it.isComplete && it.items.size == 2 && it.loadState == ThreadMyMessagesLoadState.Idle }
            assertThat(state.isLimitReached).isFalse()
            state.eventSink(ThreadMyMessagesEvent.Close)
            state = awaitState { !it.isOpen }
            state.eventSink(ThreadMyMessagesEvent.Open)
            state = awaitState { it.isOpen && it.announcement != null }
            assertThat(state.announcement).isEqualTo("Your messages in this thread, 2")
            assertThat(timeline.calls).isEqualTo(1)
            assertThat(timeline.maxActive).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `close pauses cancellation safe noncancellable page and reopen resumes only after it finishes`() = runTest {
        val timeline = RecordingThreadTimeline()
        val pageGate = CompletableDeferred<Unit>()
        timeline.page = {
            if (timeline.calls == 1) withContext(NonCancellable) { pageGate.await() }
            Result.success(timeline.calls == 2)
        }
        presenter(timeline).testWithContext {
            try {
                awaitItem().eventSink(ThreadMyMessagesEvent.Open)
                var state = awaitState { it.loadState == ThreadMyMessagesLoadState.Loading }
                state.eventSink(ThreadMyMessagesEvent.Close)
                state = awaitState { !it.isOpen }
                runCurrent()
                assertThat(timeline.calls).isEqualTo(1)
                state.eventSink(ThreadMyMessagesEvent.Open)
                state = awaitState { it.isOpen && it.announcement != null }
                runCurrent()
                assertThat(timeline.calls).isEqualTo(1)
                assertThat(timeline.maxActive).isEqualTo(1)
                pageGate.complete(Unit)
                state = awaitState { it.isComplete && it.loadState == ThreadMyMessagesLoadState.Idle }
                assertThat(state.isOpen).isTrue()
                assertThat(timeline.calls).isEqualTo(2)
                assertThat(timeline.maxActive).isEqualTo(1)
                cancelAndIgnoreRemainingEvents()
            } finally {
                pageGate.complete(Unit)
            }
        }
    }

    @Test
    fun `close cancels waiting pagination and reopening resumes without failing`() = runTest {
        val timeline = RecordingThreadTimeline().apply { backwardPaginationStatus.value = Timeline.PaginationStatus(true, true) }
        presenter(timeline).testWithContext {
            awaitItem().eventSink(ThreadMyMessagesEvent.Open)
            var state = awaitState { it.loadState == ThreadMyMessagesLoadState.Loading }
            state.eventSink(ThreadMyMessagesEvent.Close)
            state = awaitState { !it.isOpen && it.loadState == ThreadMyMessagesLoadState.Idle }
            timeline.backwardPaginationStatus.value = Timeline.PaginationStatus(false, true)
            runCurrent()
            assertThat(timeline.calls).isEqualTo(0)
            timeline.page = { Result.success(true) }
            state.eventSink(ThreadMyMessagesEvent.Open)
            state = awaitState { it.isComplete }
            assertThat(state.loadState).isEqualTo(ThreadMyMessagesLoadState.Idle)
            assertThat(timeline.calls).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `failure leaves rows usable retry resumes and clears failure`() = runTest {
        val timeline = RecordingThreadTimeline().apply {
            timelineItems.value = listOf(ownEvent(0))
            page = { Result.failure(IllegalStateException("test")) }
        }
        presenter(timeline).testWithContext {
            awaitState { it.items.size == 1 }.eventSink(ThreadMyMessagesEvent.Open)
            var state = awaitState { it.loadState == ThreadMyMessagesLoadState.Failed }
            assertThat(state.items).hasSize(1)
            state.eventSink(ThreadMyMessagesEvent.JumpTo(requireNotNull(ownEvent(0).eventId)))
            state = awaitState { it.pendingJump != null }
            assertThat(state.loadState).isEqualTo(ThreadMyMessagesLoadState.Failed)
            timeline.page = { Result.success(true) }
            state.eventSink(ThreadMyMessagesEvent.Retry)
            state = awaitState { it.isComplete && it.loadState == ThreadMyMessagesLoadState.Idle }
            assertThat(state.isOpen).isTrue()
            assertThat(timeline.calls).isEqualTo(2)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cumulative budget survives close reopen and retry including page finishing after close`() = runTest {
        val timeline = RecordingThreadTimeline()
        val gate = CompletableDeferred<Unit>()
        timeline.page = page@{
            when (timeline.calls) {
                25 -> withContext(NonCancellable) { gate.await() }
                30 -> return@page Result.failure(IllegalStateException("test"))
            }
            Result.success(false)
        }
        presenter(timeline).testWithContext {
            try {
                awaitItem().eventSink(ThreadMyMessagesEvent.Open)
                var state = awaitState { it.loadState == ThreadMyMessagesLoadState.Loading }
                runCurrent()
                assertThat(timeline.calls).isEqualTo(25)
                state.eventSink(ThreadMyMessagesEvent.Close)
                state = awaitState { !it.isOpen }
                gate.complete(Unit)
                runCurrent()
                state.eventSink(ThreadMyMessagesEvent.Open)
                state = awaitState { it.loadState == ThreadMyMessagesLoadState.Failed }
                assertThat(timeline.calls).isEqualTo(30)
                state.eventSink(ThreadMyMessagesEvent.Retry)
                state = awaitState { it.isLimitReached && it.loadState == ThreadMyMessagesLoadState.Idle }
                assertThat(state.isComplete).isFalse()
                assertThat(timeline.calls).isEqualTo(51)
                state.eventSink(ThreadMyMessagesEvent.Close)
                state = awaitState { !it.isOpen }
                state.eventSink(ThreadMyMessagesEvent.Open)
                state = awaitState { it.isOpen && it.announcement != null }
                assertThat(state.announcement).isEqualTo("Your messages in this thread, 0+")
                state.eventSink(ThreadMyMessagesEvent.Retry)
                runCurrent()
                assertThat(timeline.calls).isEqualTo(51)
                assertThat(timeline.maxActive).isEqualTo(1)
                timeline.backwardPaginationStatus.value = Timeline.PaginationStatus(false, false)
                state = awaitState { it.isComplete }
                assertThat(state.isLimitReached).isFalse()
                cancelAndIgnoreRemainingEvents()
            } finally {
                gate.complete(Unit)
            }
        }
    }

    @Test
    fun `exhausted budget survives disposing composition and presenting same instance again`() = runTest {
        val timeline = RecordingThreadTimeline().apply { page = { Result.success(false) } }
        val presenter = presenter(timeline)
        presenter.testWithContext {
            awaitItem().eventSink(ThreadMyMessagesEvent.Open)
            val state = awaitState { it.isLimitReached && it.loadState == ThreadMyMessagesLoadState.Idle }
            assertThat(state.isComplete).isFalse()
            assertThat(timeline.calls).isEqualTo(MY_MESSAGES_MAX_PAGES)
            cancelAndIgnoreRemainingEvents()
        }
        presenter.testWithContext {
            var state = awaitItem()
            assertThat(state.isOpen).isFalse()
            assertThat(state.isLimitReached).isTrue()
            assertThat(state.isComplete).isFalse()
            state.eventSink(ThreadMyMessagesEvent.Open)
            state = awaitState { it.isOpen && it.announcement != null }
            assertThat(state.announcement).isEqualTo("Your messages in this thread, 0+")
            assertThat(state.isLimitReached).isTrue()
            assertThat(state.loadState).isEqualTo(ThreadMyMessagesLoadState.Idle)
            state.eventSink(ThreadMyMessagesEvent.Retry)
            runCurrent()
            assertThat(timeline.calls).isEqualTo(MY_MESSAGES_MAX_PAGES)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `composition replacement waits for noncancellable final page and preserves exhausted budget`() = runTest {
        val timeline = RecordingThreadTimeline()
        val gate = CompletableDeferred<Unit>()
        val isPresent = MutableStateFlow(true)
        timeline.page = {
            if (timeline.calls == MY_MESSAGES_MAX_PAGES) withContext(NonCancellable) { gate.await() }
            Result.success(false)
        }
        presenter(timeline).testWithContext(isPresent) {
            try {
                awaitItem().eventSink(ThreadMyMessagesEvent.Open)
                awaitState { it.loadState == ThreadMyMessagesLoadState.Loading }
                runCurrent()
                assertThat(timeline.calls).isEqualTo(MY_MESSAGES_MAX_PAGES)
                isPresent.value = false
                runCurrent()
                expectMostRecentItem()
                isPresent.value = true
                var state = awaitState { !it.isOpen }
                assertThat(state.isLimitReached).isFalse()
                state.eventSink(ThreadMyMessagesEvent.Open)
                state = awaitState { it.isOpen }
                runCurrent()
                assertThat(timeline.calls).isEqualTo(MY_MESSAGES_MAX_PAGES)
                assertThat(timeline.active).isEqualTo(1)
                assertThat(timeline.maxActive).isEqualTo(1)
                gate.complete(Unit)
                state = awaitState { it.isLimitReached && it.loadState == ThreadMyMessagesLoadState.Idle }
                assertThat(state.isComplete).isFalse()
                assertThat(state.isOpen).isTrue()
                assertThat(timeline.calls).isEqualTo(MY_MESSAGES_MAX_PAGES)
                assertThat(timeline.active).isEqualTo(0)
                assertThat(timeline.maxActive).isEqualTo(1)
                cancelAndIgnoreRemainingEvents()
            } finally {
                gate.complete(Unit)
            }
        }
    }

    @Test
    fun `jump keeps panel open sets target and one shot jump with consumable repeatable root announcement`() = runTest {
        val timeline = RecordingThreadTimeline(hasMore = false).apply { timelineItems.value = listOf(ownEvent(0), ownEvent(1)) }
        presenter(timeline).testWithContext {
            var state = awaitState { it.items.size == 2 }
            state.eventSink(ThreadMyMessagesEvent.Open)
            state = awaitState { it.isOpen && it.announcement != null }
            assertThat(state.announcement).isEqualTo("Your messages in this thread, 2")
            state.eventSink(ThreadMyMessagesEvent.AnnouncementConsumed)
            state = awaitState { it.announcement == null }
            val rootId = requireNotNull(ownEvent(0).eventId)
            state.eventSink(ThreadMyMessagesEvent.JumpTo(rootId))
            state = awaitState { it.pendingJump == rootId && it.announcement != null }
            assertThat(state.currentEventId).isEqualTo(rootId)
            assertThat(state.isOpen).isTrue()
            assertThat(state.announcement).isEqualTo("Your message from 0 TimeOrDate true, thread root: message 0")
            state.eventSink(ThreadMyMessagesEvent.JumpConsumed)
            state.eventSink(ThreadMyMessagesEvent.AnnouncementConsumed)
            state = awaitState { it.pendingJump == null && it.announcement == null }
            assertThat(state.currentEventId).isEqualTo(rootId)
            state.eventSink(ThreadMyMessagesEvent.JumpTo(rootId))
            state = awaitState { it.pendingJump == rootId && it.announcement != null }
            state.eventSink(ThreadMyMessagesEvent.JumpTo(requireNotNull(ownEvent(1).eventId)))
            state = awaitState { it.currentEventId == ownEvent(1).eventId && it.announcement?.endsWith("message 1") == true }
            assertThat(state.announcement).isEqualTo("Your message from 1 TimeOrDate true: message 1")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unavailable event posts snackbar without changing current target or pending jump`() = runTest {
        val snackbar = SnackbarDispatcher()
        val timeline = RecordingThreadTimeline(hasMore = false).apply { timelineItems.value = listOf(ownEvent(0)) }
        presenter(timeline, snackbar = snackbar).testWithContext {
            var state = awaitState { it.items.size == 1 }
            state.eventSink(ThreadMyMessagesEvent.JumpTo(requireNotNull(ownEvent(0).eventId)))
            state = awaitState { it.pendingJump != null }
            state.eventSink(ThreadMyMessagesEvent.JumpConsumed)
            state.eventSink(ThreadMyMessagesEvent.AnnouncementConsumed)
            state = awaitState { it.pendingJump == null && it.announcement == null }
            timeline.available = false
            state.eventSink(ThreadMyMessagesEvent.JumpTo(requireNotNull(ownEvent(0).eventId)))
            runCurrent()
            assertThat(snackbar.snackbarMessage.first()?.messageResId).isEqualTo(R.string.screen_thread_my_messages_jump_failed)
            assertThat(state.currentEventId).isEqualTo(ownEvent(0).eventId)
            expectNoEvents()
            snackbar.clear()
            timeline.timelineItems.value = emptyList()
            state = awaitState { it.items.isEmpty() }
            state.eventSink(ThreadMyMessagesEvent.JumpTo(requireNotNull(ownEvent(0).eventId)))
            runCurrent()
            assertThat(snackbar.snackbarMessage.first()?.messageResId).isEqualTo(R.string.screen_thread_my_messages_jump_failed)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `previous uses chronological order oldest visible and current only while visible then steps before range arrives`() = runTest {
        val timeline = RecordingThreadTimeline(hasMore = false).apply { timelineItems.value = (0..5).map { ownEvent(it) } }
        presenter(timeline).testWithContext {
            var state = awaitState { it.items.size == 6 }
            fun id(index: Int) = requireNotNull(ownEvent(index).eventId)
            state.eventSink(ThreadMyMessagesEvent.VisibleEventsChanged(persistentListOf(id(5), id(4))))
            state = awaitState { it.previousEventId == id(3) }
            assertThat(state.items.map { it.isOnScreen }).containsExactly(true, true, false, false, false, false).inOrder()
            state.eventSink(ThreadMyMessagesEvent.JumpTo(id(5)))
            state = awaitState { it.pendingJump == id(5) }
            assertThat(state.previousEventId).isEqualTo(id(4))
            state.eventSink(ThreadMyMessagesEvent.JumpConsumed)
            state.eventSink(ThreadMyMessagesEvent.JumpToPrevious)
            state = awaitState { it.pendingJump == id(4) }
            state.eventSink(ThreadMyMessagesEvent.JumpConsumed)
            state.eventSink(ThreadMyMessagesEvent.JumpToPrevious)
            state = awaitState { it.pendingJump == id(3) }
            assertThat(state.previousEventId).isEqualTo(id(2))
            state.eventSink(ThreadMyMessagesEvent.VisibleEventsChanged(persistentListOf(id(4), id(3), id(2))))
            state = awaitState { it.items.count { it.isOnScreen } == 3 }
            assertThat(state.previousEventId).isEqualTo(id(2))
            state.eventSink(ThreadMyMessagesEvent.VisibleEventsChanged(persistentListOf(id(5), id(4))))
            state = awaitState { it.previousEventId == id(3) }
            // The old current mark is off screen; the oldest visible event now supplies the anchor.
            assertThat(state.currentEventId).isEqualTo(id(3))
            state.eventSink(ThreadMyMessagesEvent.VisibleEventsChanged(persistentListOf()))
            state = awaitState { it.previousEventId == null }
            assertThat(state.items.none { it.isOnScreen }).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `close and reopen invalidate old viewport until new reading position arrives`() = runTest {
        val timeline = RecordingThreadTimeline(hasMore = false).apply { timelineItems.value = (0..5).map { ownEvent(it) } }
        presenter(timeline).testWithContext {
            var state = awaitState { it.items.size == 6 }
            fun id(index: Int) = requireNotNull(ownEvent(index).eventId)
            state.eventSink(ThreadMyMessagesEvent.Open)
            state = awaitState { it.isOpen }
            state.eventSink(ThreadMyMessagesEvent.VisibleEventsChanged(persistentListOf(id(5), id(4))))
            state = awaitState { it.previousEventId == id(3) }
            state.eventSink(ThreadMyMessagesEvent.JumpTo(id(5)))
            state = awaitState { it.pendingJump == id(5) }
            state.eventSink(ThreadMyMessagesEvent.JumpConsumed)
            state.eventSink(ThreadMyMessagesEvent.Close)
            state = awaitState { !it.isOpen }
            assertThat(state.previousEventId).isNull()
            assertThat(state.isReadingPositionReady).isFalse()
            assertThat(state.items.none { it.isOnScreen }).isTrue()
            // The timeline scrolls to 2, 1 while reporting is disabled in the node.
            state.eventSink(ThreadMyMessagesEvent.Open)
            state = awaitState { it.isOpen }
            assertThat(state.previousEventId).isNull()
            assertThat(state.isReadingPositionReady).isFalse()
            assertThat(state.pendingJump).isNull()
            state.eventSink(ThreadMyMessagesEvent.VisibleEventsChanged(persistentListOf(id(2), id(1))))
            state = awaitState { it.isReadingPositionReady }
            assertThat(state.previousEventId).isEqualTo(id(0))
            assertThat(state.items.filter { it.isOnScreen }.map { it.eventId }).containsExactly(id(2), id(1)).inOrder()
            state.eventSink(ThreadMyMessagesEvent.JumpToPrevious)
            state = awaitState { it.pendingJump != null }
            assertThat(state.pendingJump).isEqualTo(id(0))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `previous can anchor to another sender and ignores timestamps`() = runTest {
        val other = ownEvent(3).let { it.copy(event = it.event.copy(isOwn = false, timestamp = 0)) }
        val timeline = RecordingThreadTimeline(hasMore = false).apply {
            timelineItems.value = listOf(ownEvent(0), ownEvent(1), ownEvent(2), other, ownEvent(4))
        }
        presenter(timeline).testWithContext {
            var state = awaitState { it.items.size == 4 }
            state.eventSink(ThreadMyMessagesEvent.VisibleEventsChanged(listOfNotNull(ownEvent(4).eventId, other.eventId).toImmutableList()))
            state = awaitState { it.previousEventId != null }
            assertThat(state.previousEventId).isEqualTo(ownEvent(2).eventId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deferred previous resolves once when older page arrives before completion`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val timeline = RecordingThreadTimeline().apply { timelineItems.value = listOf(ownEvent(3)) }
        timeline.page = {
            if (timeline.calls == 1) {
                gate.await()
                timeline.timelineItems.value = listOf(ownEvent(1), ownEvent(2), ownEvent(3))
                Result.success(false)
            } else {
                finish.await()
                Result.success(true)
            }
        }
        presenter(timeline).testWithContext {
            var state = awaitState { it.items.size == 1 }
            state.eventSink(ThreadMyMessagesEvent.Open)
            state = awaitState { it.loadState == ThreadMyMessagesLoadState.Loading }
            state.eventSink(ThreadMyMessagesEvent.VisibleEventsChanged(persistentListOf(requireNotNull(ownEvent(3).eventId))))
            state = awaitState { it.isReadingPositionReady }
            repeat(2) { state.eventSink(ThreadMyMessagesEvent.JumpToPrevious) }
            runCurrent()
            assertThat(state.pendingJump).isNull()
            gate.complete(Unit)
            state = awaitState { it.pendingJump != null }
            assertThat(state.pendingJump).isEqualTo(ownEvent(2).eventId)
            assertThat(state.isComplete).isFalse()
            state.eventSink(ThreadMyMessagesEvent.JumpConsumed)
            state = awaitState { it.pendingJump == null }
            finish.complete(Unit)
            state = awaitState { it.isComplete }
            assertThat(state.pendingJump).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `immediate previous supersedes queued request before older pagination arrives`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val timeline = RecordingThreadTimeline().apply { timelineItems.value = listOf(ownEvent(2), ownEvent(4), ownEvent(6)) }
        timeline.page = {
            gate.await()
            timeline.timelineItems.value = listOf(ownEvent(1), ownEvent(2), ownEvent(4), ownEvent(6))
            Result.success(true)
        }
        presenter(timeline).testWithContext {
            var state = awaitState { it.items.size == 3 }
            fun id(index: Int) = requireNotNull(ownEvent(index).eventId)
            state.eventSink(ThreadMyMessagesEvent.Open)
            state = awaitState { it.loadState == ThreadMyMessagesLoadState.Loading }
            state.eventSink(ThreadMyMessagesEvent.VisibleEventsChanged(persistentListOf(id(2))))
            state = awaitState { it.isReadingPositionReady }
            state.eventSink(ThreadMyMessagesEvent.JumpToPrevious)
            runCurrent()
            assertThat(state.pendingJump).isNull()
            state.eventSink(ThreadMyMessagesEvent.VisibleEventsChanged(persistentListOf(id(6))))
            state = awaitState { it.previousEventId == id(4) }
            state.eventSink(ThreadMyMessagesEvent.JumpToPrevious)
            state = awaitState { it.pendingJump == id(4) }
            state.eventSink(ThreadMyMessagesEvent.JumpConsumed)
            state = awaitState { it.pendingJump == null }
            assertThat(state.currentEventId).isEqualTo(id(4))
            gate.complete(Unit)
            runCurrent()
            state = expectMostRecentItem()
            assertThat(state.items).hasSize(4)
            assertThat(state.isComplete).isTrue()
            assertThat(state.pendingJump).isNull()
            assertThat(state.currentEventId).isEqualTo(id(4))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deferred previous sees older own message from final page instead of posting no earlier`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val snackbar = SnackbarDispatcher()
        val timeline = RecordingThreadTimeline().apply { timelineItems.value = listOf(ownEvent(2)) }
        timeline.page = {
            gate.await()
            timeline.timelineItems.value = listOf(ownEvent(1), ownEvent(2))
            timeline.backwardPaginationStatus.value = Timeline.PaginationStatus(false, false)
            Result.success(true)
        }
        presenter(timeline, snackbar = snackbar).testWithContext {
            var state = awaitState { it.items.size == 1 }
            state.eventSink(ThreadMyMessagesEvent.Open)
            state = awaitState { it.loadState == ThreadMyMessagesLoadState.Loading }
            state.eventSink(ThreadMyMessagesEvent.VisibleEventsChanged(persistentListOf(requireNotNull(ownEvent(2).eventId))))
            state = awaitState { it.isReadingPositionReady }
            state.eventSink(ThreadMyMessagesEvent.JumpToPrevious)
            gate.complete(Unit)
            state = awaitState { it.pendingJump != null }
            assertThat(state.pendingJump).isEqualTo(ownEvent(1).eventId)
            assertThat(snackbar.snackbarMessage.first()).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `previous pressed after completion waits for final mapping and close cancels the request`() = runTest {
        for (closeBeforeMapping in listOf(false, true)) {
            val computationTasks = ArrayDeque<Runnable>()
            val computation = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    computationTasks.addLast(block)
                }
            }
            val gate = CompletableDeferred<Unit>()
            val snackbar = SnackbarDispatcher()
            val timeline = RecordingThreadTimeline().apply { timelineItems.value = listOf(ownEvent(2)) }
            timeline.page = {
                gate.await()
                timeline.timelineItems.value = listOf(ownEvent(1), ownEvent(2))
                timeline.backwardPaginationStatus.value = Timeline.PaginationStatus(false, false)
                Result.success(true)
            }
            presenter(timeline, snackbar = snackbar, dispatchers = testCoroutineDispatchers().copy(computation = computation)).testWithContext {
                runCurrent()
                assertThat(computationTasks).isNotEmpty()
                while (computationTasks.isNotEmpty()) computationTasks.removeFirst().run()
                var state = awaitState { it.items.size == 1 }
                state.eventSink(ThreadMyMessagesEvent.Open)
                state = awaitState { it.loadState == ThreadMyMessagesLoadState.Loading }
                state.eventSink(ThreadMyMessagesEvent.VisibleEventsChanged(persistentListOf(requireNotNull(ownEvent(2).eventId))))
                state = awaitState { it.isReadingPositionReady }
                gate.complete(Unit)
                state = awaitState { it.isComplete && !it.isReadingPositionReady }
                assertThat(state.items).hasSize(1)
                assertThat(state.previousEventId).isNull()
                state.eventSink(ThreadMyMessagesEvent.JumpToPrevious)
                runCurrent()
                assertThat(snackbar.snackbarMessage.first()).isNull()
                assertThat(computationTasks).isNotEmpty()
                if (closeBeforeMapping) {
                    state.eventSink(ThreadMyMessagesEvent.Close)
                    state = awaitState { !it.isOpen }
                }
                while (computationTasks.isNotEmpty()) computationTasks.removeFirst().run()
                state = awaitState { it.items.size == 2 && (closeBeforeMapping || it.pendingJump != null) }
                runCurrent()
                assertThat(state.pendingJump).isEqualTo(if (closeBeforeMapping) null else ownEvent(1).eventId)
                assertThat(snackbar.snackbarMessage.first()).isNull()
                if (!closeBeforeMapping) {
                    state.eventSink(ThreadMyMessagesEvent.JumpConsumed)
                    state = awaitState { it.pendingJump == null }
                    state.eventSink(ThreadMyMessagesEvent.JumpToPrevious)
                    runCurrent()
                    assertThat(snackbar.snackbarMessage.first()?.messageResId).isEqualTo(R.string.screen_thread_my_messages_no_earlier)
                }
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun `deferred previous reports true end only after final page mapping finishes`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val snackbar = SnackbarDispatcher()
        val timeline = RecordingThreadTimeline().apply { timelineItems.value = listOf(ownEvent(2)) }
        timeline.page = {
            gate.await()
            Result.success(true)
        }
        presenter(timeline, snackbar = snackbar).testWithContext {
            var state = awaitState { it.items.size == 1 }
            state.eventSink(ThreadMyMessagesEvent.Open)
            state = awaitState { it.loadState == ThreadMyMessagesLoadState.Loading }
            state.eventSink(ThreadMyMessagesEvent.VisibleEventsChanged(persistentListOf(requireNotNull(ownEvent(2).eventId))))
            state = awaitState { it.isReadingPositionReady }
            state.eventSink(ThreadMyMessagesEvent.JumpToPrevious)
            gate.complete(Unit)
            state = awaitState { it.isComplete }
            runCurrent()
            assertThat(snackbar.snackbarMessage.first()?.messageResId).isEqualTo(R.string.screen_thread_my_messages_no_earlier)
            assertThat(state.pendingJump).isNull()
            snackbar.clear()
            state.eventSink(ThreadMyMessagesEvent.JumpToPrevious)
            runCurrent()
            assertThat(snackbar.snackbarMessage.first()?.messageResId).isEqualTo(R.string.screen_thread_my_messages_no_earlier)
            timeline.timelineItems.value = listOf(ownEvent(1), ownEvent(2))
            runCurrent()
            state = expectMostRecentItem()
            assertThat(state.items).hasSize(2)
            assertThat(state.pendingJump).isNull()
            assertThat(state.currentEventId).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `capped or failed previous stops waiting without claiming no history and rows remain usable`() = runTest {
        for (failure in listOf(false, true)) {
            val gate = CompletableDeferred<Unit>()
            val snackbar = SnackbarDispatcher()
            val timeline = RecordingThreadTimeline().apply {
                timelineItems.value = listOf(ownEvent(2))
                page = {
                    gate.await()
                    if (failure) Result.failure(IllegalStateException("test")) else Result.success(false)
                }
            }
            presenter(timeline, snackbar = snackbar).testWithContext {
                var state = awaitState { it.items.size == 1 }
                state.eventSink(ThreadMyMessagesEvent.Open)
                state = awaitState { it.loadState == ThreadMyMessagesLoadState.Loading }
                state.eventSink(ThreadMyMessagesEvent.VisibleEventsChanged(persistentListOf(requireNotNull(ownEvent(2).eventId))))
                state = awaitState { it.isReadingPositionReady }
                state.eventSink(ThreadMyMessagesEvent.JumpToPrevious)
                gate.complete(Unit)
                state = awaitState { it.isLimitReached || it.loadState == ThreadMyMessagesLoadState.Failed }
                runCurrent()
                assertThat(snackbar.snackbarMessage.first()).isNull()
                state.eventSink(ThreadMyMessagesEvent.JumpToPrevious)
                runCurrent()
                // An older item arriving after this terminal state must not trigger the cancelled request.
                timeline.timelineItems.value = listOf(ownEvent(1), ownEvent(2))
                state = awaitState { it.items.size == 2 }
                assertThat(state.pendingJump).isNull()
                state.eventSink(ThreadMyMessagesEvent.JumpTo(requireNotNull(ownEvent(1).eventId)))
                state = awaitState { it.pendingJump != null }
                assertThat(state.pendingJump).isEqualTo(ownEvent(1).eventId)
                assertThat(snackbar.snackbarMessage.first()).isNull()
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun `hold follows visibility switches card outline and clears on release close or removed item`() = runTest {
        val timeline = RecordingThreadTimeline(hasMore = false).apply { timelineItems.value = listOf(ownEvent(0), ownEvent(1)) }
        presenter(timeline).testWithContext {
            var state = awaitState { it.items.size == 2 }
            val id = requireNotNull(ownEvent(1).eventId)
            state.eventSink(ThreadMyMessagesEvent.Open)
            state = awaitState { it.isOpen }
            state.eventSink(ThreadMyMessagesEvent.Peek(id))
            state = awaitState { it.peek is ThreadMyMessagePeek.Card }
            assertThat(state.pendingJump).isNull()
            state.eventSink(ThreadMyMessagesEvent.VisibleEventsChanged(persistentListOf(id)))
            state = awaitState { it.peek is ThreadMyMessagePeek.Outline }
            state.eventSink(ThreadMyMessagesEvent.VisibleEventsChanged(persistentListOf()))
            state = awaitState { it.peek is ThreadMyMessagePeek.Card }
            state.eventSink(ThreadMyMessagesEvent.EndPeek)
            state = awaitState { it.peek == null }
            state.eventSink(ThreadMyMessagesEvent.Peek(id))
            state = awaitState { it.peek != null }
            state.eventSink(ThreadMyMessagesEvent.Close)
            state = awaitState { !it.isOpen }
            assertThat(state.peek).isNull()
            state.eventSink(ThreadMyMessagesEvent.Open)
            state = awaitState { it.isOpen }
            assertThat(state.peek).isNull()
            state.eventSink(ThreadMyMessagesEvent.Peek(id))
            state = awaitState { it.peek != null }
            timeline.timelineItems.value = emptyList()
            state = awaitState { it.items.isEmpty() }
            assertThat(state.peek).isNull()
            assertThat(state.pendingJump).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `preview context uses loaded predecessor and accessible action announces full content and badges`() = runTest {
        val other = ownEvent(1).let { it.copy(event = it.event.copy(isOwn = false)) }
        val timeline = RecordingThreadTimeline(hasMore = false).apply {
            timelineItems.value = listOf(ownEvent(0), other, ownEvent(2, aMessageContent("edited body\nsecond line", isEdited = true)), ownEvent(3))
        }
        presenter(timeline).testWithContext {
            var state = awaitState { it.items.size == 3 }
            assertThat(state.items.last().context).isNull()
            assertThat(state.items.first().context).isEqualTo(ThreadMyMessageContext("You", "edited body"))
            assertThat(state.items[1].context?.snippet).isEqualTo("message 1")
            assertThat(state.items[1].context?.senderName).isEqualTo(other.event.senderProfile.getDisambiguatedDisplayName(other.event.sender))
            state.eventSink(ThreadMyMessagesEvent.Preview(requireNotNull(ownEvent(2).eventId)))
            state = awaitState { it.announcement != null }
            assertThat(state.announcement).contains("You · 2 Full true")
            assertThat(state.announcement).contains("(edited)")
            assertThat(state.announcement).contains("edited body\nsecond line")
            assertThat(state.announcement).contains("After ")
            assertThat(state.peek).isNull()
            assertThat(state.pendingJump).isNull()
            state.eventSink(ThreadMyMessagesEvent.Preview(requireNotNull(ownEvent(0).eventId)))
            state = awaitState { it.announcement?.contains("Thread root") == true }
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun TestScope.presenter(
        timeline: RecordingThreadTimeline = RecordingThreadTimeline(),
        openedFromInbox: Boolean = false,
        client: FakeMatrixClient = FakeMatrixClient(),
        snackbar: SnackbarDispatcher = SnackbarDispatcher(),
        dispatchers: CoroutineDispatchers = testCoroutineDispatchers(),
    ): Presenter<ThreadMyMessagesState> {
        val presenter = ThreadMyMessagesPresenter(
            timeline = timeline,
            threadRootId = ThreadId(requireNotNull(ownEvent(0).eventId).value),
            openedFromThreadsInbox = openedFromInbox,
            matrixClient = client,
            summaryFormatter = FakePinnedMessagesBannerFormatter { event ->
                when (val content = event.content) {
                    is MessageContent -> content.body
                    is PollContent -> content.question
                    else -> "attachment"
                }
            },
            dateFormatter = FakeDateFormatter(),
            snackbarDispatcher = snackbar,
            dispatchers = dispatchers,
        )
        return presenter
    }

    private suspend fun Presenter<ThreadMyMessagesState>.testWithContext(
        isPresent: StateFlow<Boolean> = MutableStateFlow(true),
        validate: suspend TurbineTestContext<ThreadMyMessagesState>.() -> Unit,
    ) {
        channelFlow {
            moleculeFlow(RecompositionMode.Immediate) {
                CompositionLocalProvider(LocalContext provides RuntimeEnvironment.getApplication()) {
                    if (isPresent.collectAsState().value) {
                        val state = present()
                        SideEffect { trySend(state) }
                    }
                }
            }.collect {}
        }.test(validate = validate)
    }

    private suspend fun TurbineTestContext<ThreadMyMessagesState>.awaitState(
        predicate: (ThreadMyMessagesState) -> Boolean,
    ): ThreadMyMessagesState {
        var state = awaitItem()
        while (!predicate(state)) state = awaitItem()
        return state
    }
}
