/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.threads.api

import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.room.threads.InboxThread
import io.element.android.libraries.matrix.api.room.threads.InboxThreadKey
import kotlinx.coroutines.flow.StateFlow

interface ThreadsTriageQueue {
    val upNext: StateFlow<InboxThread?>
    val unreadCount: StateFlow<Int>
    val visited: StateFlow<Set<InboxThreadKey>>
    fun thread(key: InboxThreadKey): InboxThread? = null
    suspend fun open(key: InboxThreadKey): Result<EventId?>
    fun onThreadClosed(key: InboxThreadKey) {}
    fun startVisit()
    fun endVisit()
    fun setForeground(foreground: Boolean) {}
}
