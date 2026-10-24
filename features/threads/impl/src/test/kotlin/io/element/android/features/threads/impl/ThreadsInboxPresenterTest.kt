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
        val presenter = ThreadsInboxPresenter(service, FakeThreadsTriageQueue(), FakeMatrixClient(sessionCoroutineScope = backgroundScope))
        presenter.test {
            awaitItem().eventSink(ThreadsInboxEvent.MarkAllAsRead)
            val pending = awaitItem()
            assertThat(pending.pending).hasSize(1)
            pending.eventSink(ThreadsInboxEvent.Undo)
            assertThat(awaitItem().mineUnreadCount).isEqualTo(1)
            advanceTimeBy(5_001)
            runCurrent()
            assertThat(service.marked).isEmpty()
        }
    }

    @Test
    fun `mark all commits after five seconds and retains dimmed row`() = runTest {
        val service = FakeCrossRoomThreadsService()
        service.threads.value = AsyncData.Success(persistentListOf(previewThread()))
        val presenter = ThreadsInboxPresenter(service, FakeThreadsTriageQueue(), FakeMatrixClient(sessionCoroutineScope = backgroundScope))
        presenter.test {
            awaitItem().eventSink(ThreadsInboxEvent.MarkAllAsRead)
            val pending = awaitItem()
            assertThat(pending.mineUnreadCount).isEqualTo(0)
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
    fun `filter preserves visited read threads and excludes the other tab`() {
        val mine = previewThread().copy(numUnread = 0)
        assertThat(visibleThreads(listOf(mine), ThreadsInboxTab.Mine, ThreadsInboxFilter.Unread, emptySet())).isEmpty()
        assertThat(visibleThreads(listOf(mine), ThreadsInboxTab.Mine, ThreadsInboxFilter.Unread, setOf(mine.key))).containsExactly(mine)
        assertThat(visibleThreads(listOf(mine), ThreadsInboxTab.Other, ThreadsInboxFilter.All, emptySet())).isEmpty()
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
