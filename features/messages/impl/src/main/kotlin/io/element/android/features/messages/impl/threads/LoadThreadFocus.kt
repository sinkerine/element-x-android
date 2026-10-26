/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.threads

import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.Timeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeout

/** Returns false for unavailable history; lifecycle cancellation still propagates to the node. */
internal suspend fun loadThreadFocus(timeline: Timeline, eventId: EventId): Boolean = try {
    withTimeout(30_000) {
        coroutineScope {
            val subscription = Job(coroutineContext[Job])
            val items = timeline.timelineItems.stateIn(CoroutineScope(coroutineContext + subscription), SharingStarted.Eagerly, emptyList())
            try {
                while (!timeline.isEventLoaded(eventId)) {
                    val status = timeline.backwardPaginationStatus.first { !it.isPaginating }
                    check(status.hasMoreToLoad) { "Focused thread event could not be loaded" }
                    timeline.paginate(Timeline.PaginationDirection.BACKWARDS).getOrThrow()
                }
                items.first { page -> page.any { it is MatrixTimelineItem.Event && it.eventId == eventId } }
                true
            } finally {
                subscription.cancel()
            }
        }
    }
} catch (error: Exception) {
    if (error is CancellationException && error !is TimeoutCancellationException) throw error
    false
}
