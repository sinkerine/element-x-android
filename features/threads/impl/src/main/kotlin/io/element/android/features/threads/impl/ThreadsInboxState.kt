/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.threads.impl

import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.matrix.api.room.threads.InboxThread
import io.element.android.libraries.matrix.api.room.threads.InboxThreadKey
import kotlinx.collections.immutable.ImmutableList

enum class ThreadsInboxTab { Mine, Other }
enum class ThreadsInboxFilter { Unread, All }

data class ThreadsInboxState(
    val rows: AsyncData<ImmutableList<InboxThread>>,
    val tab: ThreadsInboxTab,
    val filter: ThreadsInboxFilter,
    val visited: Set<InboxThreadKey>,
    val pending: Set<InboxThreadKey>,
    val mineUnreadCount: Int,
    val otherUnreadCount: Int,
    val latestUnread: InboxThread?,
    val failed: Boolean,
    val summaries: Map<InboxThreadKey, Pair<String, String>> = emptyMap(),
    val eventSink: (ThreadsInboxEvent) -> Unit,
)

sealed interface ThreadsInboxEvent {
    data class SelectTab(val tab: ThreadsInboxTab) : ThreadsInboxEvent
    data class SelectFilter(val filter: ThreadsInboxFilter) : ThreadsInboxEvent
    data class MarkAsRead(val key: InboxThreadKey) : ThreadsInboxEvent
    data class MarkAsUnread(val key: InboxThreadKey) : ThreadsInboxEvent
    data class Touch(val key: InboxThreadKey, val pressed: Boolean) : ThreadsInboxEvent
    data object MarkAllAsRead : ThreadsInboxEvent
    data object Undo : ThreadsInboxEvent
    data object Retry : ThreadsInboxEvent
    data object RetryLoad : ThreadsInboxEvent
    data object LoadMore : ThreadsInboxEvent
}

internal fun visibleThreads(
    threads: List<InboxThread>, tab: ThreadsInboxTab, filter: ThreadsInboxFilter, visited: Set<InboxThreadKey>,
): List<InboxThread> = threads.filter {
    it.isMine == (tab == ThreadsInboxTab.Mine) &&
        (filter == ThreadsInboxFilter.All || it.isUnread || it.key in visited) &&
        (filter == ThreadsInboxFilter.All || it.isMine || it.roomNotificationMode != io.element.android.libraries.matrix.api.room.RoomNotificationMode.MUTE)
}.sortedByDescending { it.timestamp }
