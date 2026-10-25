/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.threads.impl

import java.text.DateFormat
import java.util.Date

internal fun formatInboxTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val age = (now - timestamp.coerceAtMost(now)).coerceAtLeast(0)
    return when {
        age < 60_000 -> "now"
        age < 3_600_000 -> "${age / 60_000}m"
        age < 86_400_000 -> "${age / 3_600_000}h"
        age < 604_800_000 -> "${age / 86_400_000}d"
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(timestamp))
    }
}
