/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.threads

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.matrix.test.timeline.anEventTimelineItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LoadThreadFocusTest {
    @Test
    fun `loads earlier page before focusing captured reply`() = runTest {
        val target = EventId("\$first-unread")
        val items = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val timeline = FakeTimeline(timelineItems = items)
        var loaded = false
        timeline.isEventLoadedLambda = { loaded }
        timeline.paginateLambda = {
            loaded = true
            items.value = listOf(MatrixTimelineItem.Event(UniqueId("target"), anEventTimelineItem(eventId = target)))
            Result.success(true)
        }
        assertThat(loadThreadFocus(timeline, target)).isTrue()
    }

    @Test(expected = CancellationException::class)
    fun `lifecycle cancellation propagates instead of falling back`() = runTest {
        val timeline = FakeTimeline()
        timeline.paginateLambda = { throw CancellationException("Thread closed") }
        loadThreadFocus(timeline, EventId("\$missing"))
    }

    @Test
    fun `timed out pagination falls back`() = runTest {
        val timeline = FakeTimeline()
        timeline.backwardPaginationStatus.value = Timeline.PaginationStatus(isPaginating = true, hasMoreToLoad = true)
        assertThat(loadThreadFocus(timeline, EventId("\$missing"))).isFalse()
    }

    @Test
    fun `pagination failure falls back without a captured divider`() = runTest {
        val timeline = FakeTimeline()
        timeline.paginateLambda = { Result.failure(IllegalStateException()) }
        assertThat(loadThreadFocus(timeline, EventId("\$missing"))).isFalse()
    }
}
