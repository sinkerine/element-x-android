/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.threads.impl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import dev.zacsweers.metro.Inject
import io.element.android.features.threads.api.ThreadsTriageQueue
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.architecture.map
import io.element.android.libraries.matrix.api.room.threads.CrossRoomThreadsService
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch

@Inject
class ThreadsInboxPresenter(
    private val service: CrossRoomThreadsService,
    private val queue: ThreadsTriageQueue,
    private val session: ThreadsInboxSession,
    private val summaryFormatter: InboxThreadSummaryFormatter,
) : Presenter<ThreadsInboxState> {
    @Composable
    override fun present(): ThreadsInboxState {
        val data by session.threads.collectAsState()
        val hasMore by service.hasMore.collectAsState()
        val read by session.readState.collectAsState()
        val visited by session.visited.collectAsState()
        val orderRevision by session.orderRevision.collectAsState()
        val scope = rememberCoroutineScope()
        val pagination = androidx.compose.runtime.remember { kotlinx.coroutines.sync.Mutex() }
        DisposableEffect(Unit) {
            queue.startVisit()
            session.returnedToList()
            onDispose { session.commit() }
        }
        val all = data.dataOrNull().orEmpty()
        // Reading the revision recomposes the released ordering even without a new SDK update.
        val rows = androidx.compose.runtime.remember(data, visited, read.retained, orderRevision) {
            data.map { session.ordered(it, visited + read.retained).toImmutableList() }
        }
        fun handle(event: ThreadsInboxEvent) {
            when (event) {
                is ThreadsInboxEvent.MarkAsRead -> session.mark(setOf(event.key))
                is ThreadsInboxEvent.MarkAsUnread -> session.markUnread(event.key)
                is ThreadsInboxEvent.Touch -> session.touch(event.key, event.pressed)
                ThreadsInboxEvent.MarkAllAsRead -> session.mark(all.filter { it.isUnread }.map { it.key }.toSet())
                ThreadsInboxEvent.Undo -> session.undo()
                ThreadsInboxEvent.Retry -> session.retry()
                ThreadsInboxEvent.RetryLoad -> {
                    service.endVisit()
                    service.startVisit()
                }
                ThreadsInboxEvent.LoadMore -> scope.launch {
                    if (pagination.tryLock()) {
                        try {
                        service.paginate()
                    } finally {
                        pagination.unlock()
                    }
                    }
                }
            }
        }
        androidx.compose.runtime.LaunchedEffect(rows) {
            if (!rows.dataOrNull().isNullOrEmpty()) session.rowsDisplayed()
        }
        return ThreadsInboxState(
            rows = rows,
            hasMore = hasMore,
            visited = visited,
            pending = read.pending.map { it.key }.toSet(),
            unreadCount = all.count { it.isUnread },
            failed = read.failed.isNotEmpty() || read.failedUnread.isNotEmpty(),
            summaries = all.associate { it.key to summaryFormatter.format(it) },
            eventSink = ::handle,
        )
    }
}
