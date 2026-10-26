/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.api.room.threads

import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.room.RoomNotificationMode
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.StateFlow

/** Complete session thread aggregate; unread classification uses threaded history and receipts. */
interface CrossRoomThreadsService {
    val threads: StateFlow<AsyncData<ImmutableList<InboxThread>>>
    val closedBadge: StateFlow<InboxBadgeCounts?>? get() = null
    fun startVisit()
    fun endVisit()
    suspend fun paginate(): Result<Unit>
    suspend fun markAsRead(threads: List<InboxThreadKey>): Result<Unit>
    suspend fun markSnapshotAsRead(threads: List<InboxThread>): Result<Unit> = markAsRead(threads.map { it.key })

    /** Persists a thread-specific unread flag without moving a read receipt backwards. */
    suspend fun markAsUnread(key: InboxThreadKey): Result<Unit>

    /** Consume explicit unread on opening without sending or rewinding receipts. */
    suspend fun consumeMarkedUnread(key: InboxThreadKey): Result<Unit> = Result.success(Unit)
    suspend fun firstUnreadEventId(key: InboxThreadKey): Result<EventId?>
}

data class InboxBadgeCounts(val count: Int, val hasMention: Boolean)

data class InboxThreadKey(val roomId: RoomId, val threadId: ThreadId)

data class InboxThread(
    val key: InboxThreadKey,
    val roomName: String?,
    val roomAvatarUrl: String?,
    val rootEvent: ThreadListItemEvent,
    val latestEvent: ThreadListItemEvent?,
    val numberOfReplies: Long,
    val numUnread: Long,
    val numUnreadMentions: Long,
    val isParticipating: Boolean,
    val roomNotificationMode: RoomNotificationMode,
    val readReceiptEventId: EventId?,
    val isMarkedUnread: Boolean = false,
) {
    val isMine: Boolean get() = isParticipating || numUnreadMentions > 0
    val timestamp: Long get() = latestEvent?.timestamp ?: rootEvent.timestamp
    val isUnread: Boolean get() = (numUnread > 0 || isMarkedUnread) && when (roomNotificationMode) {
        RoomNotificationMode.MUTE -> false
        RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY -> numUnreadMentions > 0
        RoomNotificationMode.ALL_MESSAGES -> true
    }
}
