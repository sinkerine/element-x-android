/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.maths.impl.layout

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MathsPrecheckTest {
    @Test
    fun `rejects blank and over length source`() {
        listOf("", " \n\t", "x".repeat(2_001)).forEach { assertThat(MathsPrecheck.isAllowed(it)).isFalse() }
        assertThat(MathsPrecheck.isAllowed("x".repeat(2_000))).isTrue()
    }

    @Test
    fun `rejects every forbidden command at non letter boundaries`() {
        MathsPrecheck.FORBIDDEN_COMMANDS.forEach { command ->
            listOf("", "{x}", " ", "1", "*").forEach { suffix ->
                assertThat(MathsPrecheck.isAllowed("x \\$command$suffix")).isFalse()
            }
            assertThat(MathsPrecheck.isAllowed("\\${command}suffix")).isTrue()
        }
    }

    @Test
    fun `allows ordinary commands`() {
        assertThat(MathsPrecheck.isAllowed("""\definecolor{red}{RGB}{255,0,0} \left(x\right)""")).isTrue()
    }
}
