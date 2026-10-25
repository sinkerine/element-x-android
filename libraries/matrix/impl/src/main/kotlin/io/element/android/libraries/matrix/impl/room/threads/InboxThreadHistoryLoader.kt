/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.room.threads

import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.Timeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeout

/** Keeps one native subscription alive while pagination changes the window. Does not send receipts. */
internal suspend fun loadInboxThreadHistory(
    timeline: Timeline,
    rootId: String,
    receiptId: EventId?,
    userId: UserId,
): InboxThreadHistory = withTimeout(30_000) {
    coroutineScope {
        val subscription = Job(coroutineContext[Job])
        val items = timeline.timelineItems.stateIn(CoroutineScope(coroutineContext + subscription), SharingStarted.Eagerly, emptyList())
        try {
            var status = timeline.backwardPaginationStatus.first { !it.isPaginating }
            val initialEnd = if (status.hasMoreToLoad) timeline.paginate(Timeline.PaginationDirection.BACKWARDS).getOrThrow() else true
            fun completeBoundary(page: List<MatrixTimelineItem>): Boolean =
                page.filterIsInstance<MatrixTimelineItem.Event>().let { events ->
                    events.any { it.eventId?.value == rootId } && (receiptId == null || events.any { it.eventId == receiptId })
                }
            val initialPage = if (initialEnd) {
                items.first { completeBoundary(it) }
            } else {
                // A cached first page can contain only a gap/deduplicated events.
                // It advances the cursor without producing a visible item.
                kotlinx.coroutines.withTimeoutOrNull(250) {
                    items.first { page -> page.any { it is MatrixTimelineItem.Event } }
                } ?: items.value
            }
            var events = initialPage.filterIsInstance<MatrixTimelineItem.Event>().map { it.event }
            while (events.none { it.eventId?.value == rootId } ||
                (receiptId != null && events.none { it.eventId == receiptId })
            ) {
                status = timeline.backwardPaginationStatus.first { !it.isPaginating }
                if (!status.hasMoreToLoad) break
                val previous = events.map { it.eventId }
                val end = timeline.paginate(Timeline.PaginationDirection.BACKWARDS).getOrThrow()
                val page = if (end) {
                    items.first { completeBoundary(it) }
                } else {
                    // Cached gap/dedup pages can advance the SDK cursor without changing visible events.
                    // Give the diff processor a bounded opportunity, then continue pagination.
                    kotlinx.coroutines.withTimeoutOrNull(250) {
                        items.first { it.filterIsInstance<MatrixTimelineItem.Event>().map { event -> event.eventId } != previous }
                    } ?: items.value
                }
                events = page.filterIsInstance<MatrixTimelineItem.Event>().map { it.event }
                if (end) break
            }
            if (receiptId != null && events.none { it.eventId == receiptId }) {
                timber.log.Timber.w(
                    "Inbox history boundary missing: root=%s receipt=%s loaded=%s",
                    rootId,
                    receiptId,
                    events.map { it.eventId }
                )
            }
            aggregateThreadHistory(events, rootId, receiptId, userId)
        } finally {
            subscription.cancel()
        }
    }
}
