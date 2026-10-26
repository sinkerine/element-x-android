/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.threads.impl

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.element.android.libraries.ui.strings.CommonStrings
import java.text.DateFormat
import java.util.Date

@Composable
internal fun inboxTimeLabel(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val age = (now - timestamp.coerceAtMost(now)).coerceAtLeast(0)
    return when {
        age < 60_000 -> stringResource(CommonStrings.screen_threads_inbox_time_now)
        age < 3_600_000 -> stringResource(CommonStrings.screen_threads_inbox_time_minutes, age / 60_000)
        age < 86_400_000 -> stringResource(CommonStrings.screen_threads_inbox_time_hours, age / 3_600_000)
        age < 604_800_000 -> stringResource(CommonStrings.screen_threads_inbox_time_days, age / 86_400_000)
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(timestamp))
    }
}
