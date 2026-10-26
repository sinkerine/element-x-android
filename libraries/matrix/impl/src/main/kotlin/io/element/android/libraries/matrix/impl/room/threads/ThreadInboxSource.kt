/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.room.threads

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.impl.RustMatrixClient
import io.element.android.libraries.matrix.impl.util.mxCallbackFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import org.matrix.rustcomponents.sdk.ReceiptThread
import org.matrix.rustcomponents.sdk.ReceiptType
import org.matrix.rustcomponents.sdk.ThreadInboxListener
import org.matrix.rustcomponents.sdk.ThreadInboxPage
import org.matrix.rustcomponents.sdk.ThreadInboxUnread

interface ThreadInboxSource {
    val updates: Flow<List<String>?>
    fun reset()
    suspend fun nextPage(): ThreadInboxPage

    /** Locally stored public and private threaded receipts of the current user. No network. */
    suspend fun receipts(roomId: RoomId, threadId: ThreadId): Set<EventId>

    /** Bounded backwards scan of replies newer than the newest receipt or own reply. */
    suspend fun unread(roomId: RoomId, threadId: ThreadId, receipts: Set<EventId>): ThreadInboxUnread
}

/** Replies scanned per unread thread before counts become lower bounds; three `/relations` pages. */
internal const val MAX_UNREAD_SCAN = 300

@ContributesBinding(SessionScope::class)
@Inject
class RustThreadInboxSource(private val client: MatrixClient) : ThreadInboxSource {
    private val inner get() = (client as RustMatrixClient).innerClient
    private var pager: org.matrix.rustcomponents.sdk.ThreadInboxPager? = null
    override val updates: Flow<List<String>?> = mxCallbackFlow {
        inner.subscribeToThreadInboxUpdates(object : ThreadInboxListener {
            override fun onUpdate(roomIds: List<String>?) {
                trySend(roomIds)
            }
        })
    }.buffer(Channel.UNLIMITED)

    override fun reset() {
        pager?.close()
        pager = null
    }

    override suspend fun nextPage(): ThreadInboxPage {
        val current = pager ?: inner.threadInboxPager(participatedOnly = true).also { pager = it }
        return current.nextPage()
    }

    override suspend fun receipts(roomId: RoomId, threadId: ThreadId): Set<EventId> {
        val room = inner.getRoom(roomId.value) ?: error("Joined room unavailable during inbox discovery")
        return room.use {
            // Outgoing privacy must never select the incoming read boundary.
            listOf(ReceiptType.READ, ReceiptType.READ_PRIVATE).mapNotNullTo(mutableSetOf()) { type ->
                room.loadUserReceipt(type, ReceiptThread.Thread(threadId.value), client.sessionId.value)?.eventId?.let(::EventId)
            }
        }
    }

    override suspend fun unread(roomId: RoomId, threadId: ThreadId, receipts: Set<EventId>): ThreadInboxUnread =
        inner.threadInboxUnread(roomId.value, threadId.value, receipts.map { it.value }, MAX_UNREAD_SCAN.toUInt())
}
