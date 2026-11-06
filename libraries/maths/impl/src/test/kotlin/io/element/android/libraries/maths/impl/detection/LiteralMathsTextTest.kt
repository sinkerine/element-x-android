/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.maths.impl.detection

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class LiteralMathsTextTest {
    @Test
    fun `flexible whitespace keeps original offsets and punctuation literal`() {
        val text = "before (a, \n\t b.*) after"
        val range = findMathsText(text, "(a, b.*)", MathsSearchBudget())!!
        assertThat(text.substring(range)).isEqualTo("(a, \n\t b.*)")
        assertThat(findMathsText("(axb)", "(a.b)", MathsSearchBudget())).isNull()
    }

    @Test(timeout = 10_000)
    fun `work budget bounds repeated prefix comparisons across searches`() {
        val budget = MathsSearchBudget()
        val text = "( ".repeat(100_000)
        val needle = "( ".repeat(10_000) + "z"
        assertThrows(IllegalStateException::class.java) { findMathsText(text, needle, budget) }
        assertThrows(IllegalStateException::class.java) { findMathsText("x", "x", budget) }
    }
}
