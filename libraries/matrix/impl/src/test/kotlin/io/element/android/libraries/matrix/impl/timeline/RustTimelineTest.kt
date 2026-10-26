/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.element.android.libraries.matrix.impl.timeline

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.item.virtual.VirtualTimelineItem
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiRoomListService
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiTimeline
import io.element.android.libraries.matrix.impl.room.RoomContentForwarder
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.room.aRoomInfo
import io.element.android.services.toolbox.api.systemclock.SystemClock
import io.element.android.services.toolbox.test.systemclock.A_FAKE_TIMESTAMP
import io.element.android.services.toolbox.test.systemclock.FakeSystemClock
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.matrix.rustcomponents.sdk.TimelineDiff
import uniffi.matrix_sdk.PaginationStatus
import org.matrix.rustcomponents.sdk.Timeline as InnerTimeline

class RustTimelineTest {
    @Test
    fun `delayed timeline receipt cannot clear newer explicit unread`() = runTest {
        val actions = io.element.android.libraries.matrix.impl.room.threads.ThreadReadActions()
        val key = io.element.android.libraries.matrix.api.room.threads.InboxThreadKey(
            io.element.android.libraries.matrix.api.core.RoomId("!room:example.org"),
            io.element.android.libraries.matrix.api.core.ThreadId("\$root"),
        )
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var unread = false
        val timeline = createRustTimeline(
            FakeFfiTimeline { release.await() },
            mode = Timeline.Mode.Thread(key.threadId),
            onThreadRead = { unread = false },
            runThreadRead = { action -> actions.run(key, action) },
        )
        val receipt = backgroundScope.launch { timeline.markAsRead(io.element.android.libraries.matrix.api.timeline.ReceiptType.READ).getOrThrow() }
        runCurrent()
        val markUnread = backgroundScope.launch { actions.run(key) { unread = true } }
        runCurrent()
        assertThat(markUnread.isCompleted).isFalse()
        release.complete(Unit)
        runCurrent()
        assertThat(receipt.isCompleted).isTrue()
        assertThat(markUnread.isCompleted).isTrue()
        assertThat(unread).isTrue()
    }

    @Test
    fun `successful threaded receipts consume explicit unread while main receipts do not`() = runTest {
        var consumed = 0
        val thread =
            createRustTimeline(
                FakeFfiTimeline(),
                mode = Timeline.Mode.Thread(io.element.android.libraries.matrix.api.core.ThreadId("\$thread")),
                onThreadRead = { consumed++ }
            )
        thread.sendReadReceipt(
            io.element.android.libraries.matrix.api.core.EventId("\$event"),
            io.element.android.libraries.matrix.api.timeline.ReceiptType.READ
        ).getOrThrow()
        thread.markAsRead(io.element.android.libraries.matrix.api.timeline.ReceiptType.READ).getOrThrow()
        assertThat(consumed).isEqualTo(2)
        val main = createRustTimeline(FakeFfiTimeline(), onThreadRead = { consumed++ })
        main.markAsRead(io.element.android.libraries.matrix.api.timeline.ReceiptType.READ).getOrThrow()
        assertThat(consumed).isEqualTo(2)
    }

    @Test
    fun `ensure that the timeline emits new loading item when pagination does not bring new events`() = runTest {
        val inner = FakeFfiTimeline()
        val systemClock = FakeSystemClock()
        val sut = createRustTimeline(
            inner = inner,
            systemClock = systemClock,
        )
        sut.timelineItems.test {
            // Give time for the listener to be set
            runCurrent()
            inner.emitDiff(
                listOf(
                    TimelineDiff.Reset(emptyList())
                )
            )
            with(awaitItem()) {
                assertThat(size).isEqualTo(2)
                // The loading
                assertThat((get(0) as MatrixTimelineItem.Virtual).virtual).isEqualTo(
                    VirtualTimelineItem.LoadingIndicator(
                        direction = Timeline.PaginationDirection.BACKWARDS,
                        timestamp = A_FAKE_TIMESTAMP,
                    )
                )
                // Typing notification
                assertThat((get(1) as MatrixTimelineItem.Virtual).virtual).isEqualTo(VirtualTimelineItem.TypingNotification)
            }
            systemClock.epochMillisResult = A_FAKE_TIMESTAMP + 1
            // Start pagination
            sut.paginate(Timeline.PaginationDirection.BACKWARDS)
            // Simulate SDK starting pagination
            inner.emitPaginationStatus(PaginationStatus.Paginating)
            // No new events received
            // Simulate SDK stopping pagination, more event to load
            inner.emitPaginationStatus(PaginationStatus.Idle(hitTimelineStart = false))
            // expect an item to be emitted, with an updated timestamp
            with(awaitItem()) {
                assertThat(size).isEqualTo(2)
                // The loading
                assertThat((get(0) as MatrixTimelineItem.Virtual).virtual).isEqualTo(
                    VirtualTimelineItem.LoadingIndicator(
                        direction = Timeline.PaginationDirection.BACKWARDS,
                        timestamp = A_FAKE_TIMESTAMP + 1,
                    )
                )
                // Typing notification
                assertThat((get(1) as MatrixTimelineItem.Virtual).virtual).isEqualTo(VirtualTimelineItem.TypingNotification)
            }
        }
    }
}

private fun TestScope.createRustTimeline(
    inner: InnerTimeline,
    mode: Timeline.Mode = Timeline.Mode.Live,
    systemClock: SystemClock = FakeSystemClock(),
    joinedRoom: JoinedRoom = FakeJoinedRoom().apply { givenRoomInfo(aRoomInfo()) },
    coroutineScope: CoroutineScope = backgroundScope,
    dispatcher: CoroutineDispatcher = testCoroutineDispatchers().io,
    roomContentForwarder: RoomContentForwarder = RoomContentForwarder(FakeFfiRoomListService()),
    onThreadRead: suspend () -> Unit = {},
    runThreadRead: suspend (suspend () -> Unit) -> Unit = { action -> action() },
): RustTimeline {
    return RustTimeline(
        inner = inner,
        mode = mode,
        systemClock = systemClock,
        joinedRoom = joinedRoom,
        coroutineScope = coroutineScope,
        dispatcher = dispatcher,
        roomContentForwarder = roomContentForwarder,
        onThreadRead = onThreadRead,
        runThreadRead = runThreadRead,
    )
}
