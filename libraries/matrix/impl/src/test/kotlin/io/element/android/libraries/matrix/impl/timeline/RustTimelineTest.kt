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
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.room.IntentionalMention
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
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.matrix.rustcomponents.sdk.Mentions
import org.matrix.rustcomponents.sdk.MessageContent
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.RoomMessageEventContentWithoutRelation
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.contentWithoutRelationFromMessage
import uniffi.matrix_sdk.PaginationStatus
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import org.matrix.rustcomponents.sdk.Timeline as InnerTimeline

class RustTimelineTest {
    @Test
    fun `plain text extra content forwards body mentions and extra JSON for main and thread sends and replies`() = runTest {
        mockkStatic(::contentWithoutRelationFromMessage)
        try {
            val delegateDispatcher = testCoroutineDispatchers().io
            var onTimelineDispatcher = false
            val dispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    delegateDispatcher.dispatch(context) {
                        onTimelineDispatcher = true
                        try {
                            block.run()
                        } finally {
                            onTimelineDispatcher = false
                        }
                    }
                }
            }
            val input = slot<MessageContent>()
            val baseContent = mockk<RoomMessageEventContentWithoutRelation>()
            val content = mockk<RoomMessageEventContentWithoutRelation>(relaxed = true)
            val mentions = slot<Mentions>()
            every { contentWithoutRelationFromMessage(capture(input)) } returns baseContent
            every { baseContent.withMentions(capture(mentions)) } returns content
            val body = "/model **literal** <tag> --provider openrouter"
            val bot = UserId("@bot:example.org")
            val extra = """{"org.matrix.msc4391.command":{"command":"model"}}"""
            for (mode in listOf(Timeline.Mode.Live, Timeline.Mode.Thread(ThreadId("\$root")))) {
                for (reply in listOf(null, EventId("\$reply"))) {
                    for (extraJson in listOf(extra, null)) {
                        var sends = 0
                        var replies = 0
                        val inner = FakeFfiTimeline().apply {
                            sendWithExtraContentLambda = { actualContent, actualExtra ->
                                sends++
                                assertThat(onTimelineDispatcher).isTrue()
                                assertThat(actualContent).isSameInstanceAs(content)
                                assertThat(actualExtra).isEqualTo(extraJson)
                                mockk()
                            }
                            sendReplyWithExtraContentLambda = { actualContent, id, actualExtra, addMentions ->
                                replies++
                                assertThat(onTimelineDispatcher).isTrue()
                                assertThat(actualContent).isSameInstanceAs(content)
                                assertThat(id).isEqualTo(reply?.value)
                                assertThat(actualExtra).isEqualTo(extraJson)
                                assertThat(addMentions).isFalse()
                                mockk()
                            }
                        }
                        val timeline = createRustTimeline(inner, mode = mode, dispatcher = dispatcher)
                        timeline.sendPlainTextWithExtraContent(body, listOf(IntentionalMention.User(bot)), extraJson, reply).getOrThrow()
                        assertThat(sends).isEqualTo(if (reply == null) 1 else 0)
                        assertThat(replies).isEqualTo(if (reply == null) 0 else 1)
                        assertThat(input.captured.body).isEqualTo(body)
                        val text = (input.captured.msgType as MessageType.Text).content
                        assertThat(text.body).isEqualTo(body)
                        assertThat(text.formatted).isNull()
                        assertThat(mentions.captured.userIds).containsExactly(bot.value)
                        assertThat(mentions.captured.room).isFalse()
                    }
                }
            }
            verify(exactly = 8) { content.destroy() }
        } finally {
            unmockkStatic(::contentWithoutRelationFromMessage)
        }
    }

    @Test
    fun `plain text extra content wraps send and construction failures and destroys content`() = runTest {
        mockkStatic(::contentWithoutRelationFromMessage)
        try {
            val failure = IllegalStateException("SDK failure")
            val baseContent = mockk<RoomMessageEventContentWithoutRelation>()
            val content = mockk<RoomMessageEventContentWithoutRelation>(relaxed = true)
            every { contentWithoutRelationFromMessage(any()) } returns baseContent
            every { baseContent.withMentions(any()) } returns content
            val inner = FakeFfiTimeline().apply {
                sendWithExtraContentLambda = { _, _ -> throw failure }
                sendReplyWithExtraContentLambda = { _, _, _, _ -> throw failure }
            }
            val timeline = createRustTimeline(inner)
            for (reply in listOf(null, EventId("\$reply"))) {
                val result = timeline.sendPlainTextWithExtraContent("/model", emptyList(), "{}", reply)
                assertThat(result.exceptionOrNull()).isSameInstanceAs(failure)
            }
            verify(exactly = 2) { content.destroy() }
            val cancellation = CancellationException("SDK cancellation")
            inner.sendWithExtraContentLambda = { _, _ -> throw cancellation }
            inner.sendReplyWithExtraContentLambda = { _, _, _, _ -> throw cancellation }
            for (reply in listOf(null, EventId("\$reply"))) {
                val thrown = try {
                    timeline.sendPlainTextWithExtraContent("/model", emptyList(), null, reply)
                    null
                } catch (exception: CancellationException) {
                    exception
                }
                assertThat(thrown).isInstanceOf(CancellationException::class.java)
            }
            verify(exactly = 4) { content.destroy() }
            every { contentWithoutRelationFromMessage(any()) } throws failure
            assertThat(timeline.sendPlainTextWithExtraContent("/model", emptyList(), null, null).exceptionOrNull())
                .isSameInstanceAs(failure)
        } finally {
            unmockkStatic(::contentWithoutRelationFromMessage)
        }
    }

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
