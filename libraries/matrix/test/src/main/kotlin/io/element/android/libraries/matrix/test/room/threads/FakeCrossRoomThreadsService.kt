/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.test.room.threads

import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.room.threads.CrossRoomThreadsService
import io.element.android.libraries.matrix.api.room.threads.InboxThread
import io.element.android.libraries.matrix.api.room.threads.InboxThreadKey
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow

class FakeCrossRoomThreadsService : CrossRoomThreadsService {
    override val threads = MutableStateFlow<AsyncData<ImmutableList<InboxThread>>>(AsyncData.Success(persistentListOf()))
    val marked = mutableListOf<List<InboxThreadKey>>()
    var markResult: Result<Unit> = Result.success(Unit)
    var markHandler: ((List<InboxThreadKey>) -> Result<Unit>)? = null
    val snapshots = mutableListOf<List<InboxThread>>()
    var firstUnreadResult: Result<EventId?> = Result.success(null)
    val consumedUnread = mutableListOf<InboxThreadKey>()
    var visiting = false
    override fun startVisit() {
        visiting = true
    }
    override fun endVisit() {
        visiting = false
    }
    override suspend fun paginate(): Result<Unit> = Result.success(Unit)
    override suspend fun markAsRead(threads: List<InboxThreadKey>): Result<Unit> {
        marked.add(threads)
        return markHandler?.invoke(threads) ?: markResult
    }
    override suspend fun markSnapshotAsRead(threads: List<InboxThread>): Result<Unit> {
        snapshots.add(threads)
        return markAsRead(threads.map { it.key })
    }
    override suspend fun markAsUnread(key: InboxThreadKey): Result<Unit> = markResult
    override suspend fun consumeMarkedUnread(key: InboxThreadKey): Result<Unit> {
        consumedUnread.add(key)
        return Result.success(Unit)
    }
    override suspend fun firstUnreadEventId(key: InboxThreadKey): Result<EventId?> = firstUnreadResult
}
