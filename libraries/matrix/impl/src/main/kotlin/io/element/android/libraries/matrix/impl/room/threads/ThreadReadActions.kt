/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.room.threads

import io.element.android.libraries.matrix.api.room.threads.InboxThreadKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes receipt requests and explicit-unread writes across every entry point. */
internal class ThreadReadActions {
    val successfulTimelineReads = kotlinx.coroutines.flow.MutableSharedFlow<InboxThreadKey>(extraBufferCapacity = 64)

    suspend fun timelineReadSucceeded(key: InboxThreadKey) {
        successfulTimelineReads.emit(key)
    }

    private val locks = mutableMapOf<InboxThreadKey, Mutex>()

    suspend fun <T> run(key: InboxThreadKey, action: suspend () -> T): T {
        val lock = synchronized(locks) { locks.getOrPut(key) { Mutex() } }
        return lock.withLock { action() }
    }
}
