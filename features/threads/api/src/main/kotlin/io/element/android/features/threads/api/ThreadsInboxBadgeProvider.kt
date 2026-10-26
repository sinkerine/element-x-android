/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.threads.api

import kotlinx.coroutines.flow.StateFlow

data class ThreadsBadge(val count: Int = 0, val hasMention: Boolean = false)

interface ThreadsInboxBadgeProvider {
    val badge: StateFlow<ThreadsBadge>
}
