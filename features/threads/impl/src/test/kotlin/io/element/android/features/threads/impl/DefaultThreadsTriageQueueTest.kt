/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.threads.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.room.threads.FakeCrossRoomThreadsService
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultThreadsTriageQueueTest {
    @Test
    fun `new activity in visited thread reenters queue after moving to another thread`() = runTest {
        val service = FakeCrossRoomThreadsService()
        val a = previewThread()
        val b = a.copy(key = a.key.copy(threadId = ThreadId("\$b")))
        service.threads.value = AsyncData.Success(persistentListOf(a, b))
        val client = FakeMatrixClient(sessionCoroutineScope = backgroundScope)
        val session = ThreadsInboxSession(service, client)
        val queue = DefaultThreadsTriageQueue(service, client, session, io.element.android.libraries.designsystem.utils.snackbar.SnackbarDispatcher())
        runCurrent()
        queue.open(a.key)
        queue.open(b.key)
        val reply = a.rootEvent.copy(eventId = EventId("\$new"), timestamp = 100)
        service.threads.value = AsyncData.Success(persistentListOf(a.copy(latestEvent = reply), b))
        runCurrent()
        assertThat(queue.upNext.value?.key).isEqualTo(a.key)
        assertThat(session.visited.value).contains(a.key)
        queue.onThreadClosed(a.key)
        assertThat(session.openThread.value).isEqualTo(b.key)
        queue.onThreadClosed(b.key)
        runCurrent()
        assertThat(session.openThread.value).isNull()
        assertThat(queue.upNext.value?.key).isEqualTo(a.key)
    }

    @Test
    fun `explicit mark unread of visited thread returns it to latest queue`() = runTest {
        val service = FakeCrossRoomThreadsService()
        val thread = previewThread()
        service.threads.value = AsyncData.Success(persistentListOf(thread))
        val client = FakeMatrixClient(sessionCoroutineScope = backgroundScope)
        val session = ThreadsInboxSession(service, client)
        val queue = DefaultThreadsTriageQueue(service, client, session, io.element.android.libraries.designsystem.utils.snackbar.SnackbarDispatcher())
        runCurrent()
        queue.open(thread.key)
        runCurrent()
        assertThat(queue.upNext.value).isNull()
        session.markUnread(thread.key)
        service.threads.value = AsyncData.Success(persistentListOf(thread.copy(isMarkedUnread = true)))
        runCurrent()
        assertThat(queue.upNext.value?.key).isEqualTo(thread.key)
    }

    @Test
    fun `opening explicit unread consumes persistent flag without committing receipts`() = runTest {
        val service = FakeCrossRoomThreadsService()
        val thread = previewThread().copy(isMarkedUnread = true, numUnread = 0)
        service.threads.value = AsyncData.Success(persistentListOf(thread))
        val client = FakeMatrixClient(sessionCoroutineScope = backgroundScope)
        val queue =
            DefaultThreadsTriageQueue(
                service,
                client,
                ThreadsInboxSession(service, client),
                io.element.android.libraries.designsystem.utils.snackbar.SnackbarDispatcher()
            )
        runCurrent()
        queue.open(thread.key).getOrThrow()
        assertThat(service.consumedUnread).containsExactly(thread.key)
        assertThat(service.marked).isEmpty()
    }

    @Test
    fun `failed first unread capture skips opened thread and posts fallback feedback`() = runTest {
        val service = FakeCrossRoomThreadsService()
        val mine = previewThread()
        service.threads.value = AsyncData.Success(persistentListOf(mine))
        service.firstUnreadResult = Result.failure(IllegalStateException("history unavailable"))
        val client = FakeMatrixClient(sessionCoroutineScope = backgroundScope)
        val snackbar = io.element.android.libraries.designsystem.utils.snackbar.SnackbarDispatcher()
        val queue = DefaultThreadsTriageQueue(service, client, ThreadsInboxSession(service, client), snackbar)
        runCurrent()
        assertThat(queue.open(mine.key).isFailure).isTrue()
        runCurrent()
        assertThat(queue.upNext.value).isNull()
        assertThat(queue.visited.value).containsExactly(mine.key)
        assertThat(snackbar.snackbarMessage.first()?.messageResId)
            .isEqualTo(io.element.android.libraries.ui.strings.CommonStrings.screen_threads_inbox_focus_failed)
    }

    @Test
    fun `mine precedes newer other and opening skips current thread`() = runTest {
        val service = FakeCrossRoomThreadsService()
        val mine = previewThread()
        val other = mine.copy(
            key = mine.key.copy(threadId = ThreadId("\$other")),
            isParticipating = false,
            rootEvent = mine.rootEvent.copy(timestamp = 100),
        )
        service.threads.value = AsyncData.Success(persistentListOf(other, mine))
        val client = FakeMatrixClient(sessionCoroutineScope = backgroundScope)
        val queue = DefaultThreadsTriageQueue(
            service,
            client,
            ThreadsInboxSession(service, client),
            io.element.android.libraries.designsystem.utils.snackbar.SnackbarDispatcher()
        )
        runCurrent()
        assertThat(queue.upNext.value).isEqualTo(mine)
        assertThat(queue.unreadCount.value).isEqualTo(2)
        val firstUnread = EventId("\$first-unread")
        service.firstUnreadResult = Result.success(firstUnread)
        assertThat(queue.open(mine.key).getOrThrow()).isEqualTo(firstUnread)
        runCurrent()
        assertThat(queue.upNext.value).isEqualTo(other)
        queue.open(other.key)
        runCurrent()
        assertThat(queue.upNext.value).isNull()
        queue.endVisit()
        runCurrent()
        assertThat(queue.visited.value).isEmpty()
    }
}
