/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.threads.impl

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InboxTimeTest {
    @Test
    fun `time clamps future values and selects largest fitting unit`() {
        val now = 1_000_000_000L
        assertThat(formatInboxTime(now + 1, now)).isEqualTo("now")
        assertThat(formatInboxTime(now - 240_000, now)).isEqualTo("4m")
        assertThat(formatInboxTime(now - 10_800_000, now)).isEqualTo("3h")
        assertThat(formatInboxTime(now - 172_800_000, now)).isEqualTo("2d")
        assertThat(formatInboxTime(now - 700_000_000, now)).doesNotMatch("[0-9]+d")
    }
}
