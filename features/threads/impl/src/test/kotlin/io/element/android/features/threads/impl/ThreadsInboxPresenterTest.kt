/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.threads.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.features.threads.test.FakeThreadsTriageQueue
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.matrix.api.room.RoomNotificationMode
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.room.threads.FakeCrossRoomThreadsService
import io.element.android.tests.testutils.test
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadsInboxPresenterTest {
    @Test
    fun `mark all undo sends no receipts`() = runTest {
        val service = FakeCrossRoomThreadsService()
        service.threads.value = AsyncData.Success(persistentListOf(previewThread()))
        val client = FakeMatrixClient(sessionCoroutineScope = backgroundScope)
        val presenter = ThreadsInboxPresenter(
            service,
            FakeThreadsTriageQueue(),
            ThreadsInboxSession(service, client),
            InboxThreadSummaryFormatter(io.element.android.libraries.eventformatter.test.FakeRoomLatestEventFormatter())
        )
        presenter.test {
            awaitItem().eventSink(ThreadsInboxEvent.MarkAllAsRead)
            var pending = awaitItem()
            while (pending.pending.isEmpty()) pending = awaitItem()
            assertThat(pending.pending).hasSize(1)
            pending.eventSink(ThreadsInboxEvent.Undo)
            assertThat(awaitItem().unreadCount).isEqualTo(1)
            advanceTimeBy(5_001)
            runCurrent()
            assertThat(service.marked).isEmpty()
        }
    }

    @Test
    fun `mark all commits after five seconds and retains dimmed row`() = runTest {
        val service = FakeCrossRoomThreadsService()
        service.threads.value = AsyncData.Success(persistentListOf(previewThread()))
        val client = FakeMatrixClient(sessionCoroutineScope = backgroundScope)
        val presenter = ThreadsInboxPresenter(
            service,
            FakeThreadsTriageQueue(),
            ThreadsInboxSession(service, client),
            InboxThreadSummaryFormatter(io.element.android.libraries.eventformatter.test.FakeRoomLatestEventFormatter())
        )
        presenter.test {
            awaitItem().eventSink(ThreadsInboxEvent.MarkAllAsRead)
            var pending = awaitItem()
            while (pending.unreadCount != 0) pending = awaitItem()
            assertThat(pending.unreadCount).isEqualTo(0)
            assertThat(pending.rows.dataOrNull()).hasSize(1)
            advanceTimeBy(4_999)
            runCurrent()
            assertThat(service.marked).isEmpty()
            advanceTimeBy(2)
            runCurrent()
            assertThat(service.marked).hasSize(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `threads are ordered unread first then newest and visited rows keep their place`() {
        val newRead = previewThread().copy(numUnread = 0, latestEvent = null)
        val oldUnread = newRead.copy(key = newRead.key.copy(threadId = io.element.android.libraries.matrix.api.core.ThreadId("\$old")), numUnread = 2)
        val newest = newRead.rootEvent.timestamp
        val read = newRead.copy(rootEvent = newRead.rootEvent.copy(timestamp = newest + 10))
        val unread = oldUnread.copy(rootEvent = oldUnread.rootEvent.copy(timestamp = newest))
        assertThat(orderThreads(listOf(read, unread), emptySet())).containsExactly(unread, read).inOrder()
        val visitedRead = unread.copy(numUnread = 0)
        assertThat(orderThreads(listOf(read, visitedRead), setOf(visitedRead.key))).containsExactly(visitedRead, read).inOrder()
        assertThat(orderThreads(listOf(visitedRead, read), emptySet())).containsExactly(read, visitedRead).inOrder()
    }

    @Test
    fun `mark all marks every loaded unread thread`() = runTest {
        val mine = previewThread()
        val second = mine.copy(key = mine.key.copy(threadId = io.element.android.libraries.matrix.api.core.ThreadId("\$second")))
        val read = mine.copy(key = mine.key.copy(threadId = io.element.android.libraries.matrix.api.core.ThreadId("\$read")), numUnread = 0)
        val service = FakeCrossRoomThreadsService()
        service.threads.value = AsyncData.Success(persistentListOf(mine, second, read))
        val client = FakeMatrixClient(sessionCoroutineScope = backgroundScope)
        ThreadsInboxPresenter(
            service,
            FakeThreadsTriageQueue(),
            ThreadsInboxSession(service, client),
            InboxThreadSummaryFormatter(io.element.android.libraries.eventformatter.test.FakeRoomLatestEventFormatter())
        ).test {
            val first = awaitItem()
            assertThat(first.rows.dataOrNull()).hasSize(3)
            first.eventSink(ThreadsInboxEvent.MarkAllAsRead)
            var state = awaitItem()
            while (state.pending.isEmpty() || state.unreadCount != 0) state = awaitItem()
            assertThat(state.pending).containsExactly(mine.key, second.key)
            state.eventSink(ThreadsInboxEvent.Undo)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `mentions qualify as mine and notification modes gate unread`() {
        val other = previewThread().copy(isParticipating = false)
        assertThat(other.isMine).isFalse()
        assertThat(other.copy(numUnreadMentions = 1).isMine).isTrue()
        assertThat(other.copy(roomNotificationMode = RoomNotificationMode.MUTE).isUnread).isFalse()
        assertThat(other.copy(roomNotificationMode = RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY).isUnread).isFalse()
        assertThat(other.copy(roomNotificationMode = RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY, numUnreadMentions = 1).isUnread).isTrue()
    }
}
