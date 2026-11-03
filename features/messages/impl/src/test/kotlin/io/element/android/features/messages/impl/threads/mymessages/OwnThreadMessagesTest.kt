/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.threads.mymessages

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.media.ImageInfo
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.TimelineException
import io.element.android.libraries.matrix.api.timeline.item.event.EventContent
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.matrix.api.timeline.item.event.LocationMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.StickerContent
import io.element.android.libraries.matrix.api.timeline.item.event.UnableToDecryptContent
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.matrix.test.timeline.aMessageContent
import io.element.android.libraries.matrix.test.timeline.aPollContent
import io.element.android.libraries.matrix.test.timeline.aProfileChangeMessageContent
import io.element.android.libraries.matrix.test.timeline.aRedactedContent
import io.element.android.libraries.matrix.test.timeline.anEventTimelineItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OwnThreadMessagesTest {
    @Test
    fun `filter includes every own remote listable content with chronological indices`() {
        val contents = listOf(
            aMessageContent(),
            aMessageContent(messageType = LocationMessageType("location", "geo:1,2", null, null)),
            aMessageContent("latest edit", isEdited = true),
            aPollContent(isEdited = true),
            StickerContent("sticker", null, ImageInfo(null, null, null, null, null, null, null), MediaSource("mxc://example/a"), null),
        )
        val items = listOf(MatrixTimelineItem.Other) + contents.mapIndexed { index, content -> ownEvent(index, content) }
        val filtered = items.ownThreadMessages()
        assertThat(filtered.map { it.index }).containsExactly(1, 2, 3, 4, 5).inOrder()
        assertThat(filtered.map { it.value.content }).containsExactlyElementsIn(contents).inOrder()
    }

    @Test
    fun `filter excludes other users local echoes failed sent echoes missing IDs redactions UTD and state`() {
        val own = ownEvent(0)
        val events = listOf(
            own.copy(event = own.event.copy(isOwn = false)),
            own.copy(event = own.event.copy(isRemote = false, eventId = null)),
            own.copy(event = own.event.copy(isRemote = false, localSendState = LocalEventSendState.Failed.Unknown("failed"))),
            own.copy(event = own.event.copy(isRemote = false, localSendState = LocalEventSendState.Sent(requireNotNull(own.eventId)))),
            own.copy(event = own.event.copy(eventId = null)),
            ownEvent(1, aRedactedContent()),
            ownEvent(2, UnableToDecryptContent(UnableToDecryptContent.Data.Unknown, null)),
            ownEvent(3, aProfileChangeMessageContent()),
            MatrixTimelineItem.Other,
        )
        assertThat(events.ownThreadMessages()).isEmpty()
    }

    @Test
    fun `loader stops immediately at start`() = runTest {
        val timeline = RecordingThreadTimeline(hasMore = false)
        assertThat(loadWholeThread(timeline).getOrThrow()).isTrue()
        assertThat(timeline.calls).isEqualTo(0)
    }

    @Test
    fun `loader stops when page reaches start even before status updates`() = runTest {
        val timeline = RecordingThreadTimeline().apply { page = { Result.success(calls == 3) } }
        var loaded = 0
        assertThat(loadWholeThread(timeline, onPageLoaded = { loaded++ }).getOrThrow()).isTrue()
        assertThat(timeline.calls).isEqualTo(3)
        assertThat(loaded).isEqualTo(3)
    }

    @Test
    fun `loader caps successful pages without marking complete`() = runTest {
        val timeline = RecordingThreadTimeline().apply { page = { Result.success(false) } }
        assertThat(loadWholeThread(timeline).getOrThrow()).isFalse()
        assertThat(timeline.calls).isEqualTo(MY_MESSAGES_MAX_PAGES)
    }

    @Test
    fun `last allowed page can reach start`() = runTest {
        val timeline = RecordingThreadTimeline().apply { page = { Result.success(calls == 2) } }
        assertThat(loadWholeThread(timeline, maxPages = 2).getOrThrow()).isTrue()
        assertThat(timeline.calls).isEqualTo(2)
    }

    @Test
    fun `loader stops on returned or thrown failure without counting failed pages`() = runTest {
        val error = IllegalStateException("test")
        val timeline = RecordingThreadTimeline().apply { page = { Result.failure(error) } }
        var loaded = 0
        assertThat(loadWholeThread(timeline, onPageLoaded = { loaded++ }).exceptionOrNull()).isSameInstanceAs(error)
        timeline.page = { throw error }
        assertThat(loadWholeThread(timeline).exceptionOrNull()).isSameInstanceAs(error)
        assertThat(loaded).isEqualTo(0)
        assertThat(timeline.calls).isEqualTo(2)
    }

    @Test
    fun `loader waits for concurrent pagination before requesting a page`() = runTest {
        val timeline = RecordingThreadTimeline().apply {
            backwardPaginationStatus.value = Timeline.PaginationStatus(true, true)
            page = { Result.success(true) }
        }
        val load = async { loadWholeThread(timeline) }
        runCurrent()
        assertThat(timeline.calls).isEqualTo(0)
        timeline.backwardPaginationStatus.value = Timeline.PaginationStatus(false, true)
        assertThat(load.await().getOrThrow()).isTrue()
        assertThat(timeline.calls).isEqualTo(1)
    }

    @Test
    fun `external pagination reaching start avoids another request`() = runTest {
        val timeline = RecordingThreadTimeline().apply { backwardPaginationStatus.value = Timeline.PaginationStatus(true, true) }
        val load = async { loadWholeThread(timeline) }
        runCurrent()
        timeline.backwardPaginationStatus.value = Timeline.PaginationStatus(false, false)
        assertThat(load.await().getOrThrow()).isTrue()
        assertThat(timeline.calls).isEqualTo(0)
    }

    @Test
    fun `pagination race waits and retries without spending page budget`() = runTest {
        val timeline = RecordingThreadTimeline().apply {
            page = {
                if (calls == 1) {
                    backwardPaginationStatus.value = Timeline.PaginationStatus(true, true)
                    Result.failure(TimelineException.CannotPaginate)
                } else {
                    Result.success(true)
                }
            }
        }
        var loaded = 0
        val load = async { loadWholeThread(timeline, maxPages = 1, onPageLoaded = { loaded++ }) }
        runCurrent()
        assertThat(timeline.calls).isEqualTo(1)
        timeline.backwardPaginationStatus.value = Timeline.PaginationStatus(false, true)
        assertThat(load.await().getOrThrow()).isTrue()
        assertThat(loaded).isEqualTo(1)
        assertThat(timeline.calls).isEqualTo(2)
    }

    @Test
    fun `pagination rejection resuming after competing page is idle retries without spending budget`() = runTest {
        val rejectionGate = CompletableDeferred<Unit>()
        val timeline = RecordingThreadTimeline().apply {
            page = {
                if (calls == 1) {
                    backwardPaginationStatus.value = Timeline.PaginationStatus(true, true)
                    rejectionGate.await()
                    Result.failure(TimelineException.CannotPaginate)
                } else {
                    Result.success(false)
                }
            }
        }
        var loaded = 0
        val load = async { loadWholeThread(timeline, maxPages = 1, onPageLoaded = { loaded++ }) }
        runCurrent()
        assertThat(timeline.calls).isEqualTo(1)
        timeline.timelineItems.value = listOf(ownEvent(0))
        timeline.backwardPaginationStatus.value = Timeline.PaginationStatus(false, true)
        rejectionGate.complete(Unit)
        assertThat(load.await().getOrThrow()).isFalse()
        assertThat(loaded).isEqualTo(1)
        assertThat(timeline.calls).isEqualTo(2)
    }

    @Test
    fun `repeated contention backs off and cancellation stops retries`() = runTest {
        val timeline = RecordingThreadTimeline().apply { page = { Result.failure(TimelineException.CannotPaginate) } }
        val load = async { loadWholeThread(timeline) }
        runCurrent()
        assertThat(timeline.calls).isEqualTo(1)
        advanceTimeBy(99)
        runCurrent()
        assertThat(timeline.calls).isEqualTo(1)
        advanceTimeBy(1)
        runCurrent()
        assertThat(timeline.calls).isEqualTo(2)
        load.cancel()
        runCurrent()
        assertThat(load.isCancelled).isTrue()
        advanceTimeBy(100)
        runCurrent()
        assertThat(timeline.calls).isEqualTo(2)
    }

    @Test
    fun `cancellation while waiting propagates`() = runTest {
        val timeline = RecordingThreadTimeline().apply { backwardPaginationStatus.value = Timeline.PaginationStatus(true, true) }
        val load = async { loadWholeThread(timeline) }
        runCurrent()
        load.cancel()
        runCurrent()
        assertThat(load.isCancelled).isTrue()
        assertThat(timeline.calls).isEqualTo(0)
    }

    @Test
    fun `returned cancellation is rethrown`() = runTest {
        val timeline = RecordingThreadTimeline().apply { page = { Result.failure(CancellationException("cancelled")) } }
        val load = async { loadWholeThread(timeline) }
        runCurrent()
        assertThat(load.isCancelled).isTrue()
        assertThat(timeline.calls).isEqualTo(1)
    }
}

internal fun ownEvent(index: Int, content: EventContent = aMessageContent("message $index")): MatrixTimelineItem.Event = MatrixTimelineItem.Event(
    UniqueId("own-$index"),
    anEventTimelineItem(eventId = EventId("\$own$index"), content = content, isOwn = true, isRemote = true, timestamp = index.toLong()),
)

internal class RecordingThreadTimeline(hasMore: Boolean = true) : Timeline by FakeTimeline() {
    override val timelineItems = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
    override val backwardPaginationStatus = MutableStateFlow(Timeline.PaginationStatus(false, hasMore))
    var page: suspend () -> Result<Boolean> = { awaitCancellation() }
    var calls = 0
    var active = 0
    var maxActive = 0
    var available = true

    override suspend fun paginate(direction: Timeline.PaginationDirection): Result<Boolean> {
        assertThat(direction).isEqualTo(Timeline.PaginationDirection.BACKWARDS)
        calls++
        active++
        maxActive = maxOf(maxActive, active)
        return try {
            page()
        } finally {
            active--
        }
    }

    override suspend fun isEventLoaded(eventId: EventId): Boolean = available && timelineItems.value.any {
        (it as? MatrixTimelineItem.Event)?.eventId == eventId
    }
}
