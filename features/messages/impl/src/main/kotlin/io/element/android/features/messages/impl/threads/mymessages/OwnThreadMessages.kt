/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.threads.mymessages

import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.TimelineException
import io.element.android.libraries.matrix.api.timeline.item.event.EventTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.PollContent
import io.element.android.libraries.matrix.api.timeline.item.event.StickerContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

internal const val MY_MESSAGES_MAX_PAGES = 50

/** Own listable events with their chronological position in [this]. */
internal fun List<MatrixTimelineItem>.ownThreadMessages(): List<IndexedValue<EventTimelineItem>> =
    mapIndexedNotNull { index, item ->
        val event = (item as? MatrixTimelineItem.Event)?.event ?: return@mapIndexedNotNull null
        if (!event.isOwn || !event.isRemote || event.eventId == null) return@mapIndexedNotNull null
        if (event.content is MessageContent || event.content is StickerContent || event.content is PollContent) {
            IndexedValue(index, event)
        } else {
            null
        }
    }

/** Paginates backwards until the start or [maxPages]; returns true when the start was reached. Rethrows cancellation. */
internal suspend fun loadWholeThread(
    timeline: Timeline,
    maxPages: Int = MY_MESSAGES_MAX_PAGES,
    onPageLoaded: () -> Unit = {},
): Result<Boolean> {
    return try {
        var pages = 0
        while (pages < maxPages) {
            currentCoroutineContext().ensureActive()
            val status = timeline.backwardPaginationStatus.first { !it.isPaginating }
            if (!status.hasMoreToLoad) return Result.success(true)
            val result = timeline.paginate(Timeline.PaginationDirection.BACKWARDS)
            if (result.isSuccess) {
                pages++
                onPageLoaded()
            }
            // The SDK pagination call is non-cancellable; do not start another page after Close.
            currentCoroutineContext().ensureActive()
            if (result.exceptionOrNull() == TimelineException.CannotPaginate) {
                // Competing pagination may already be idle again. Back off before checking its status and retrying.
                delay(100)
                continue
            }
            if (result.getOrThrow()) return Result.success(true)
        }
        Result.success(!timeline.backwardPaginationStatus.first { !it.isPaginating }.hasMoreToLoad)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Result.failure(error)
    }
}
