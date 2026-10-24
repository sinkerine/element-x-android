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
class DefaultThreadsTriageQueue(private val service: CrossRoomThreadsService, client: MatrixClient) : ThreadsTriageQueue {
    override val upNext = MutableStateFlow<InboxThread?>(null)
    override val unreadCount = MutableStateFlow(0)
    override val visited = MutableStateFlow<Set<InboxThreadKey>>(emptySet())
    init {
        client.sessionCoroutineScope.launch {
            combine(service.threads, visited) { data, opened ->
                val unread = data.dataOrNull().orEmpty().filter { it.isUnread }
                unreadCount.value = unread.size
                upNext.value = unread.filter { it.key !in opened }.sortedWith(
                    compareByDescending<InboxThread> { it.isMine }.thenByDescending { it.timestamp }
                ).firstOrNull()
            }.collect {}
        }
    }
    override suspend fun open(key: InboxThreadKey): Result<EventId?> {
        visited.value += key
        return service.firstUnreadEventId(key)
    }
    override fun startVisit() = service.startVisit()
    override fun endVisit() {
        visited.value = emptySet()
        service.endVisit()
    }
}
