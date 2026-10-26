/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.threads.impl

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.threads.api.ThreadsTriageQueue
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.room.threads.CrossRoomThreadsService
import io.element.android.libraries.matrix.api.room.threads.InboxThread
import io.element.android.libraries.matrix.api.room.threads.InboxThreadKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class)
@Inject
class DefaultThreadsTriageQueue(
    private val service: CrossRoomThreadsService,
    client: MatrixClient,
    private val session: ThreadsInboxSession,
    private val snackbar: io.element.android.libraries.designsystem.utils.snackbar.SnackbarDispatcher,
) : ThreadsTriageQueue {
    override val upNext = MutableStateFlow<InboxThread?>(null)
    override val unreadCount = MutableStateFlow(0)
    override val visited = session.visited
    init {
        client.sessionCoroutineScope.launch {
            combine(session.threads, session.openThread) { data, opened ->
                val unread = data.dataOrNull().orEmpty().filter { it.isUnread }
                unreadCount.value = unread.size
                upNext.value = unread.filter { it.key != opened }.sortedWith(
                    compareByDescending<InboxThread> { it.isMine }.thenByDescending { it.timestamp }
                ).firstOrNull()
            }.collect {}
        }
    }
    override fun thread(key: InboxThreadKey): InboxThread? = session.threads.value.dataOrNull()?.find { it.key == key }

    override suspend fun open(key: InboxThreadKey): Result<EventId?> {
        val thread = session.threads.value.dataOrNull()?.find { it.key == key }
            ?: return Result.failure(IllegalStateException("Thread unavailable"))
        session.opened(thread)
        val captured = service.firstUnreadEventId(key)
        if (thread.isMarkedUnread) {
            val consumed = service.consumeMarkedUnread(key)
            if (consumed.isFailure) return Result.failure(requireNotNull(consumed.exceptionOrNull()))
        }
        return captured.onFailure {
            timber.log.Timber.w(it, "Could not capture first unread thread event")
            snackbar.post(io.element.android.libraries.designsystem.utils.snackbar.SnackbarMessage(
                io.element.android.libraries.ui.strings.CommonStrings.screen_threads_inbox_focus_failed,
            ))
        }
    }
    override fun onThreadClosed(key: InboxThreadKey) {
        if (session.openThread.value == key) session.returnedToList()
    }
    override fun startVisit() = session.startVisit()
    override fun endVisit() = session.endVisit()
    override fun setForeground(foreground: Boolean) = session.setForeground(foreground)
}
