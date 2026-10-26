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

data class ThreadsInboxState(
    val rows: AsyncData<ImmutableList<InboxThread>>,
    val visited: Set<InboxThreadKey>,
    val pending: Set<InboxThreadKey>,
    val unreadCount: Int,
    val failed: Boolean,
    val summaries: Map<InboxThreadKey, Pair<String, String>> = emptyMap(),
    val hasMore: Boolean = false,
    val eventSink: (ThreadsInboxEvent) -> Unit,
)

sealed interface ThreadsInboxEvent {
    data class MarkAsRead(val key: InboxThreadKey) : ThreadsInboxEvent
    data class MarkAsUnread(val key: InboxThreadKey) : ThreadsInboxEvent
    data class Touch(val key: InboxThreadKey, val pressed: Boolean) : ThreadsInboxEvent
    data object MarkAllAsRead : ThreadsInboxEvent
    data object Undo : ThreadsInboxEvent
    data object Retry : ThreadsInboxEvent
    data object RetryLoad : ThreadsInboxEvent
    data object LoadMore : ThreadsInboxEvent
}

/** Unread first, then most recent. [sticky] rows (read during this visit) keep their unread position. */
internal fun orderThreads(threads: List<InboxThread>, sticky: Set<InboxThreadKey>): List<InboxThread> =
    threads.sortedWith(compareByDescending<InboxThread> { it.isUnread || it.key in sticky }.thenByDescending { it.timestamp })
