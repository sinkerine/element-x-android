/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.maths.impl.detection

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.random.Random

class MathsDelimitersTest {
    @Test
    fun `approved Web and Android acceptance cases`() {
        val cases = listOf(
            """Area $\pi r^2$""" to listOf("""\pi r^2"""),
            "Identity (\$a+b\$) holds" to listOf("a+b"),
            """Euler ${'$'}e^{i\pi}+1=0$""" to listOf("""e^{i\pi}+1=0"""),
            """Energy ${'$'}E=mc^2$ and \(a\,b\)

\[\frac{1}{2}\]""".trimIndent() to listOf("E=mc^2", """a\,b""", """\frac{1}{2}"""),
            """$$\sum_k k$$""" to listOf("""\sum_k k"""),
            """$$\int_0^1 x\,dx$$""" to listOf("""\int_0^1 x\,dx"""),
            """\(\alpha\)""" to listOf("""\alpha"""),
            "Tickets are \$5/\$10" to emptyList(),
            "Range \$5-\$10" to emptyList(),
            "\$3,\$4" to emptyList(),
            "I paid \$5 and \$10" to emptyList(),
            """\${'$'}x\$""" to emptyList(),
            "Use `\$x\$`" to emptyList(),
        )
        cases.forEach { (source, expected) ->
            assertWithMessage(source).that(MathsDelimiters.find(source).map { it.tex }).containsExactlyElementsIn(expected)
                .inOrder()
        }
    }

    @Test
    fun `masks backtick spans and fences without changing offsets`() {
        listOf("`\$x\$`", "``\$x\$ ` \$y\$``", "```kotlin\n\$x\$\n```", "```\n\$x\$").forEach {
            assertThat(MathsDelimiters.find(it)).isEmpty()
        }
        val source = "`\$x\$` then \$y\$"
        assertThat(MathsDelimiters.find(source)).containsExactly(MathsMatch(11..13, "y", false))
        assertThat(MathsDelimiters.find("unclosed ` then \$x\$").map { it.tex }).containsExactly("x")
    }

    @Test
    fun `fences use valid lines and matching or longer closing runs`() {
        listOf("```\ntext ``` \$x\$", "   ```\n\$x\$", "```\n```` trailing \$x\$").forEach {
            assertThat(MathsDelimiters.find(it)).isEmpty()
        }
        listOf("```\n\$x\$\n````\n\$y\$", "   ```lang\n\$x\$\n  ``` \t\n\$y\$").forEach {
            assertThat(MathsDelimiters.find(it).map { match -> match.tex }).containsExactly("y")
        }
        assertThat(MathsDelimiters.find("\\` \$x\$ \\`").map { it.tex }).containsExactly("x")
    }

    @Test
    fun `Markdown links mask labels and destinations with nested parentheses`() {
        listOf("[link \$x\$](https://e.org)", "[link \$x\$](url(a(b)))", "[link \$x\$][ref]").forEach {
            assertThat(MathsDelimiters.find(it)).isEmpty()
            assertThat(MathsDelimiters.find("$it then \$y\$").map { match -> match.tex }).containsExactly("y")
        }
    }

    @Test
    fun `matches are returned and capped in document order rather than pattern order`() {
        val source = "\$a\$\n\\(b\\)\n\$\$c\$\$\n\\[d\\]"
        val matches = MathsDelimiters.find(source)
        assertThat(matches.map { it.tex }).containsExactly("a", "b", "c", "d").inOrder()
        assertThat(matches.map { it.displayMode }).containsExactly(false, false, true, true).inOrder()
        val capped = MathsDelimiters.find((1..51).joinToString("\n") { "\$x$it\$" } + "\n\$\$last\$\$")
        assertThat(capped).hasSize(50)
        assertThat(capped.last().tex).isEqualTo("x50")
    }

    @Test
    fun `earlier matches block enclosing delimiters without hiding later maths`() {
        val source = """\(before ${'$'}x$ after\) then \(y\)"""
        assertThat(MathsDelimiters.find(source).map { it.tex }).containsExactly("x", "y").inOrder()
        assertThat(MathsDelimiters.find(source)).containsExactlyElementsIn(regexMatches(source)).inOrder()
        val result = DefaultMathsDetector().detect(source, null)!!
        assertThat(result.select("[data-mx-maths]").map { it.attr("data-mx-maths") }).containsExactly("x", "y").inOrder()
    }

    @Test(timeout = 10_000)
    fun `long adversarial inputs avoid recursive regex and repeated suffix scans`() {
        assertThat(MathsDelimiters.find("\$" + "x".repeat(100_000) + "\$").single().tex).hasLength(100_000)
        assertThat(MathsDelimiters.find("\\(".repeat(50_000))).isEmpty()
        assertThat(MathsDelimiters.find("\$\$" + "\\x".repeat(50_000) + "\$\$").single().displayMode).isTrue()
        assertThat(MathsDelimiters.find("`" + "x".repeat(100_000))).isEmpty()
        assertThat(MathsDelimiters.find("\\[".repeat(50_000))).isEmpty()
        assertThat(MathsDelimiters.find("\$" + "\\\$".repeat(50_000))).isEmpty()
        assertThat(MathsDelimiters.find("[".repeat(100_000) + "\$x\$" + "]".repeat(100_000) + "(url)")).isEmpty()
        assertThat(MathsDelimiters.find("```\n" + "text ``` \$x\$\n".repeat(10_000))).isEmpty()
    }

    @Test
    fun `iterative implementation agrees with approved patterns on bounded inputs`() {
        val random = Random(621)
        val alphabet =
            listOf("a", "b", " ", "\n", "\r\n", "\t", "$", "\\", "(", ")", "[", "]", "1", ",", "'", "$$", "\\(", "\\)", "\r", "\u0085", "\u2028", "\u2029")
        val sources = buildList {
            repeat(100_000) {
                add(buildString { repeat(random.nextInt(1, 70)) { append(alphabet.random(random)) } })
            }
            listOf("a", "a ", "a\\$", "a\\", "\\$", "a\\$\\$", "a\nb", "").forEach { tex ->
                listOf("$" to "$", "$$" to "$$", "\\(" to "\\)", "\\[" to "\\]").forEach { (open, close) ->
                    add("$open$tex$close")
                    add("before $open$tex$close after")
                }
            }
        }
        sources.forEach { source ->
            assertWithMessage(source).that(MathsDelimiters.find(source)).containsExactlyElementsIn(regexMatches(source)).inOrder()
        }
    }

    private fun regexMatches(source: String): List<MathsMatch> {
        var masked = source.toCharArray().also(::maskMarkdown).concatToString()
        val result = mutableListOf<MathsMatch>()
        val patterns = listOf(MathsDelimiters.TEX_DISPLAY, MathsDelimiters.TEX_INLINE, MathsDelimiters.LATEX_DISPLAY, MathsDelimiters.LATEX_INLINE)
        patterns.forEachIndexed { index, pattern ->
            val regex = Regex(pattern, setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
            val matches = mutableListOf<MathsMatch>()
            var cursor = 0
            while (cursor < masked.length) {
                val match = regex.find(masked, cursor) ?: break
                val range = match.range.first + match.groupValues[1].length..match.range.last
                if ('\u0000' in masked.substring(range)) {
                    cursor = match.range.first + 1
                } else {
                    matches.add(MathsMatch(range, match.groupValues[2], index % 2 == 0))
                    cursor = match.range.last + 1
                }
            }
            result.addAll(matches)
            val chars = masked.toCharArray()
            matches.forEach { chars.fill('\u0000', it.range.first, it.range.last + 1) }
            masked = String(chars)
        }
        return result.sortedBy { it.range.first }.take(50)
    }
}
