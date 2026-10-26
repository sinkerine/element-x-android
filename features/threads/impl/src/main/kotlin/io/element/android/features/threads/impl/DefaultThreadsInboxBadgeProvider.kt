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
import io.element.android.features.threads.api.ThreadsBadge
import io.element.android.features.threads.api.ThreadsInboxBadgeProvider
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class)
@Inject
class DefaultThreadsInboxBadgeProvider(
    session: ThreadsInboxSession,
    client: MatrixClient,
    service: io.element.android.libraries.matrix.api.room.threads.CrossRoomThreadsService,
) : ThreadsInboxBadgeProvider {
    override val badge = combine(session.threads, session.isVisiting, service.closedBadge ?: MutableStateFlow(null)) { data, visiting, closed ->
        val unread = data.dataOrNull().orEmpty().filter { it.isUnread }
        // A partial scan is not an account-wide unread total. Show exact badges only after completion.
        if (!visiting && closed != null) {
            ThreadsBadge(if (closed.isComplete) closed.count else 0, closed.hasMention)
        } else {
            ThreadsBadge(if (data.isSuccess()) unread.size else 0, unread.any { it.numUnreadMentions > 0 })
        }
    }.stateIn(client.sessionCoroutineScope, SharingStarted.Eagerly, ThreadsBadge())
}
