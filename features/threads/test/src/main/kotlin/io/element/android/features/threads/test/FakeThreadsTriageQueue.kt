/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.threads.test

import io.element.android.features.threads.api.ThreadsTriageQueue
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.room.threads.InboxThread
import io.element.android.libraries.matrix.api.room.threads.InboxThreadKey
import kotlinx.coroutines.flow.MutableStateFlow

class FakeThreadsTriageQueue : ThreadsTriageQueue {
    override val upNext = MutableStateFlow<InboxThread?>(null)
    override val unreadCount = MutableStateFlow(0)
    override val visited = MutableStateFlow<Set<InboxThreadKey>>(emptySet())
    override suspend fun open(key: InboxThreadKey): Result<EventId?> {
        visited.value += key
        return Result.success(null)
    }
    override fun startVisit() = Unit
    override fun endVisit() {
        visited.value = emptySet()
    }
}
