/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.room.threads

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.matrix.test.timeline.anEventTimelineItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class InboxThreadHistoryLoaderTest {
    private fun event(id: String, timestamp: Long, own: Boolean = false) = MatrixTimelineItem.Event(
        UniqueId(id),
        anEventTimelineItem(eventId = EventId("\$$id"), timestamp = timestamp, isOwn = own),
    )

    @Test
    fun `empty new timeline requests history before waiting for events`() = runTest {
        val items = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val timeline = FakeTimeline(timelineItems = items)
        var calls = 0
        timeline.paginateLambda = {
            calls++
            items.value = listOf(event("root", 0, true), event("reply", 1))
            timeline.backwardPaginationStatus.value = Timeline.PaginationStatus(false, false)
            Result.success(true)
        }
        val result = loadInboxThreadHistory(timeline, "\$root", null, UserId("@own:example.org"))
        assertThat(calls).isEqualTo(1)
        assertThat(result.firstUnread).isEqualTo(EventId("\$reply"))
        assertThat(result.participating).isTrue()
    }

    @Test
    fun `history loads backwards through receipt and root before selecting first unread`() = runTest {
        val items = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val timeline = FakeTimeline(timelineItems = items)
        var calls = 0
        timeline.paginateLambda = {
            calls++
            items.value = if (calls == 1) {
                listOf(event("root", 0, true), event("new", 3))
            } else {
                listOf(
                event("root", 0, true),
                    event("receipt", 1),
                    event("first-unread", 2),
                    event("new", 3),
            )
            }
            val end = calls == 2
            timeline.backwardPaginationStatus.value = Timeline.PaginationStatus(false, !end)
            Result.success(end)
        }
        val result = loadInboxThreadHistory(timeline, "\$root", EventId("\$receipt"), UserId("@own:example.org"))
        assertThat(calls).isEqualTo(2)
        assertThat(result.unreadCount).isEqualTo(2)
        assertThat(result.firstUnread).isEqualTo(EventId("\$first-unread"))
    }

    @Test
    fun `end reached waits for delayed processed diff containing receipt`() = runTest {
        val items = MutableStateFlow<List<MatrixTimelineItem>>(listOf(event("new", 3)))
        val timeline = FakeTimeline(timelineItems = items)
        timeline.paginateLambda = {
            timeline.backwardPaginationStatus.value = Timeline.PaginationStatus(false, false)
            backgroundScope.launch {
                kotlinx.coroutines.delay(100)
                items.value = listOf(event("root", 0, true), event("receipt", 1), event("new", 3))
            }
            Result.success(true)
        }
        val result = loadInboxThreadHistory(timeline, "\$root", EventId("\$receipt"), UserId("@own:example.org"))
        assertThat(result.firstUnread).isEqualTo(EventId("\$new"))
    }

    @Test
    fun `cached dedup page advances cursor even when visible events do not change`() = runTest {
        val items = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val timeline = FakeTimeline(timelineItems = items)
        var calls = 0
        timeline.paginateLambda = {
            calls++
            if (calls == 1) items.value = listOf(event("new", 3))
            if (calls == 3) items.value = listOf(event("root", 0, true), event("receipt", 1), event("new", 3))
            timeline.backwardPaginationStatus.value = Timeline.PaginationStatus(false, calls < 3)
            Result.success(calls == 3)
        }
        val result = loadInboxThreadHistory(timeline, "\$root", EventId("\$receipt"), UserId("@own:example.org"))
        assertThat(calls).isEqualTo(3)
        assertThat(result.firstUnread).isEqualTo(EventId("\$new"))
    }

    @Test(expected = IllegalStateException::class)
    fun `failed pagination fails capture instead of inventing a divider`() = runTest {
        val timeline = FakeTimeline()
        timeline.paginateLambda = { Result.failure(IllegalStateException()) }
        loadInboxThreadHistory(timeline, "\$root", null, UserId("@own:example.org"))
    }
}
