/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.threads.test

import io.element.android.features.threads.api.ThreadsBadge
import io.element.android.features.threads.api.ThreadsInboxBadgeProvider
import kotlinx.coroutines.flow.MutableStateFlow

class FakeThreadsInboxBadgeProvider : ThreadsInboxBadgeProvider {
    override val badge = MutableStateFlow(ThreadsBadge())
}
