/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.maths.impl.detection

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UnescapeMarkdownTest {
    @Test
    fun `unknown entity names with recognized prefixes remain literal`() {
        listOf("&notit;", "&ampersand;", "&copycat;").forEach { unknown ->
            assertThat(normalizeMarkdownText(unknown, MathsSearchBudget())).isEqualTo(unknown)
            val source = "$unknown &amp; &#38; &#x26; \\&amp;"
            assertThat(normalizeMarkdownText(source, MathsSearchBudget())).isEqualTo("$unknown & & & &amp;")
        }
        assertThat(normalizeMarkdownText("&not; &Not; &copy;", MathsSearchBudget())).isEqualTo("¬ ⫬ ©")
    }

    @Test
    fun `visible normalization processes escapes and entities once in source order`() {
        assertThat(normalizeMarkdownText("""\&amp; &amp; \&#38; &#x26;""", MathsSearchBudget()))
            .isEqualTo("&amp; & &#38; &")
        assertThat(normalizeMarkdownText("""&#92;(a\,b) &amp;amp;""", MathsSearchBudget()))
            .isEqualTo("""\(a,b) &amp;""")
        assertThat(normalizeMarkdownText("""\\&amp; &bogus; &#12345678;""", MathsSearchBudget()))
            .isEqualTo("""\& &bogus; &#12345678;""")
    }

    @Test
    fun `unescapes every ASCII punctuation character only`() {
        for (char in ' '..'~') {
            val punctuation = char in '!'..'/' || char in ':'..'@' || char in '['..'`' || char in '{'..'~'
            assertThat(unescapeMarkdown("\\$char")).isEqualTo(if (punctuation) "$char" else "\\$char")
        }
        assertThat(unescapeMarkdown("\\é\\中\\\n\\")).isEqualTo("\\é\\中\\\n\\")
    }

    @Test
    fun `escapes are removed once and TeX commands survive`() {
        assertThat(unescapeMarkdown("""\(\alpha\,b\) \\(x\\)""")).isEqualTo("""(\alpha,b) \(x\)""")
    }
}
