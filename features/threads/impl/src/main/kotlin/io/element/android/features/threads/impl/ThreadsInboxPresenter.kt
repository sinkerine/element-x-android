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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Inject
import io.element.android.features.threads.api.ThreadsTriageQueue
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.room.threads.CrossRoomThreadsService
import io.element.android.libraries.matrix.api.room.threads.InboxThreadKey
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Inject
class ThreadsInboxPresenter(
    private val service: CrossRoomThreadsService,
    private val queue: ThreadsTriageQueue,
    private val client: MatrixClient,
) : Presenter<ThreadsInboxState> {
    @Composable
    override fun present(): ThreadsInboxState {
        val data by service.threads.collectAsState()
        val visited by queue.visited.collectAsState()
        val next by queue.upNext.collectAsState()
        val scope = rememberCoroutineScope()
        var tab by remember { mutableStateOf(ThreadsInboxTab.Mine) }
        var filter by remember { mutableStateOf(ThreadsInboxFilter.Unread) }
        var pending by remember { mutableStateOf<Set<InboxThreadKey>>(emptySet()) }
        var marked by remember { mutableStateOf<Set<InboxThreadKey>>(emptySet()) }
        var failedBatch by remember { mutableStateOf<Set<InboxThreadKey>>(emptySet()) }
        var timer by remember { mutableStateOf<Job?>(null) }

        fun commit() {
            timer?.cancel()
            val batch = pending
            pending = emptySet()
            if (batch.isEmpty()) return
            client.sessionCoroutineScope.launch {
                service.markAsRead(batch.toList()).onFailure {
                    marked -= batch
                    failedBatch = batch
                }
            }
        }
        DisposableEffect(Unit) {
            queue.startVisit()
            onDispose {
                commit()
                // Navigation to triage keeps the Home node alive; switching Home items ends the visit.
                queue.endVisit()
            }
        }
        val all = data.dataOrNull().orEmpty()
        val effective = all.map { if (it.key in marked) it.copy(numUnread = 0, numUnreadMentions = 0) else it }
        fun mark(keys: Set<InboxThreadKey>) {
            commit()
            pending = keys
            marked += keys
            timer = scope.launch {
                delay(5_000)
                commit()
            }
        }
        fun handle(event: ThreadsInboxEvent) = androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            when (event) {
                is ThreadsInboxEvent.SelectTab -> tab = event.tab
                is ThreadsInboxEvent.SelectFilter -> filter = event.filter
                is ThreadsInboxEvent.MarkAsRead -> mark(setOf(event.key))
                ThreadsInboxEvent.MarkAllAsRead -> mark(effective.filter { it.isUnread && it.isMine == (tab == ThreadsInboxTab.Mine) }.map { it.key }.toSet())
                ThreadsInboxEvent.Undo -> {
                    timer?.cancel()
                    marked -= pending
                    pending = emptySet()
                }
                ThreadsInboxEvent.Retry -> {
                    val batch = failedBatch
                    failedBatch = emptySet()
                    mark(batch)
                    commit()
                }
                ThreadsInboxEvent.LoadMore -> scope.launch { service.paginate() }
            }
        }
        return ThreadsInboxState(
            rows = when {
                data is AsyncData.Failure -> AsyncData.Failure((data as AsyncData.Failure).error)
                data.dataOrNull() == null -> AsyncData.Loading()
                else -> AsyncData.Success(visibleThreads(effective, tab, filter, visited + marked).toImmutableList())
            },
            tab = tab,
            filter = filter,
            visited = visited,
            pending = pending,
            mineUnreadCount = effective.count { it.isMine && it.isUnread },
            otherUnreadCount = effective.count { !it.isMine && it.isUnread },
            latestUnread = next?.takeIf { it.key !in marked },
            failed = failedBatch.isNotEmpty(),
            eventSink = ::handle,
        )
    }
}
