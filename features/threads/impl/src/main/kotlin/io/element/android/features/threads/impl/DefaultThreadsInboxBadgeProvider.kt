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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class)
@Inject
class DefaultThreadsInboxBadgeProvider(session: ThreadsInboxSession, client: MatrixClient) : ThreadsInboxBadgeProvider {
    override val badge = session.threads.map { data ->
        val unread = data.dataOrNull().orEmpty().filter { it.isUnread }
        ThreadsBadge(unread.size, unread.any { it.numUnreadMentions > 0 })
    }.stateIn(client.sessionCoroutineScope, SharingStarted.Eagerly, ThreadsBadge())
}
