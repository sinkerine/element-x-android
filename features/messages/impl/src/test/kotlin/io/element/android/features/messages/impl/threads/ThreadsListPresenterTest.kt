/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.threads

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.fixtures.aTimelineItemContentFactory
import io.element.android.features.messages.impl.messagesummary.FakeMessageSummaryFormatter
import io.element.android.features.messages.impl.threads.list.ThreadsListEvent
import io.element.android.features.messages.impl.threads.list.ThreadsListPresenter
import io.element.android.features.messages.impl.threads.list.aThreadListItem
import io.element.android.libraries.dateformatter.test.FakeDateFormatter
import io.element.android.libraries.matrix.test.AN_AVATAR_URL
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_ROOM_NAME
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.room.threads.FakeThreadsListService
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ThreadsListPresenterTest {
    @Test
    fun `present - initial state`() = runTest {
        createThreadsListPresenter().test {
            var state = awaitItem()
            assertThat(state.roomId).isEqualTo(A_ROOM_ID)
            assertThat(state.roomName).isEqualTo(A_ROOM_NAME)
            assertThat(state.roomAvatarUrl).isEqualTo(AN_AVATAR_URL)
            while (state.isLoading) state = awaitItem()
            assertThat(state.threads).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `present - paginate`() = runTest {
        val paginateRecorder = lambdaRecorder<Result<Unit>> { Result.success(Unit) }
        val threadsListService = FakeThreadsListService(paginate = paginateRecorder)
        val room = FakeJoinedRoom(threadsListService = threadsListService)
        createThreadsListPresenter(room).test {
            var initialItem = awaitItem()
            while (initialItem.isLoading) initialItem = awaitItem()

            // Pagination is automatically triggered on start, so we should have one call to paginate already
            paginateRecorder.assertions().isCalledOnce()

            initialItem.eventSink(ThreadsListEvent.Paginate)
            runCurrent()

            // Simulate a pagination result
            threadsListService.emit(listOf(aThreadListItem()))

            // We should have a second call to paginate after the event is sent
            paginateRecorder.assertions().isCalledExactly(2)

            // And we receive the new items
            var updatedState = awaitItem()
            while (updatedState.threads.isEmpty()) updatedState = awaitItem()
            assertThat(updatedState.threads).isNotEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - initial load can be retried`() = runTest {
        var shouldFail = true
        var attempts = 0
        val service = FakeThreadsListService(
            paginate = {
                attempts++
                if (shouldFail) Result.failure(IllegalStateException()) else Result.success(Unit)
            },
        )
        createThreadsListPresenter(FakeJoinedRoom(threadsListService = service)).test {
            var state = awaitItem()
            while (!state.initialLoadFailed) state = awaitItem()
            assertThat(attempts).isEqualTo(1)
            shouldFail = false
            state.eventSink(ThreadsListEvent.RetryInitialLoad)
            do {
                state = awaitItem()
            } while (state.isLoading || state.initialLoadFailed)
            assertThat(attempts).isEqualTo(2)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - pagination failure can be retried`() = runTest {
        var attempts = 0
        val service = FakeThreadsListService(
            paginate = {
                attempts++
                if (attempts == 2) Result.failure(IllegalStateException()) else Result.success(Unit)
            },
        )
        createThreadsListPresenter(FakeJoinedRoom(threadsListService = service)).test {
            var state = awaitItem()
            while (state.isLoading) state = awaitItem()
            service.emit(listOf(aThreadListItem()))
            while (state.threads.isEmpty()) state = awaitItem()
            state.eventSink(ThreadsListEvent.Paginate)
            while (!state.paginationFailed) state = awaitItem()
            assertThat(state.threads).isNotEmpty()
            state.eventSink(ThreadsListEvent.RetryPagination)
            do {
                state = awaitItem()
            } while (state.isPaginating || state.paginationFailed)
            assertThat(attempts).isEqualTo(3)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun createThreadsListPresenter(
        room: FakeJoinedRoom = FakeJoinedRoom(),
    ): ThreadsListPresenter {
        return ThreadsListPresenter(
            room = room,
            timelineItemContentFactory = aTimelineItemContentFactory(),
            messageSummaryFormatter = FakeMessageSummaryFormatter(),
            dateFormatter = FakeDateFormatter(),
        )
    }
}
