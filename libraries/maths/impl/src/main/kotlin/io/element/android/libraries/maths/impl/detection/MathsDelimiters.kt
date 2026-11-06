/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.maths.impl.detection

import io.element.android.libraries.maths.api.MAX_MATHS_ELEMENTS_PER_MESSAGE

/** [range] includes delimiters, but excludes the leading context character. */
internal data class MathsMatch(val range: IntRange, val tex: String, val displayMode: Boolean)

internal object MathsDelimiters {
    // Element Web's approved patterns, with MULTILINE and DOT_MATCHES_ALL. Groups 1 and 2 are context and TeX.
    const val TEX_DISPLAY = """(^)\$\$(([^$]|\\\$)+?)\$\$$"""
    const val TEX_INLINE = """(^|[\s.,!?:;(\[{"'])(?!\\)\$(?!\s)(([^$\n]|\\\$)*([^\\\s\$]|\\\$)(?:\\\$)?)\$(?!\d)"""
    const val LATEX_DISPLAY = """(^)\\\[(?!\\\])(.*?)\\\]$"""
    const val LATEX_INLINE = """(^|[^\\])\\\((?!\\\))(.*?)\\\)"""

    /**
     * Iterative equivalents avoid the JVM regex engine's recursive repetitions on untrusted, arbitrarily long TeX.
     * Masks are hard barriers: earlier patterns win over any enclosing delimiters found by a later pattern.
     * HTML ambiguity checks need overflow ranges too, so they are not mistaken for literal text.
     */
    fun find(source: String, limit: Int = MAX_MATHS_ELEMENTS_PER_MESSAGE): List<MathsMatch> {
        val masked = source.toCharArray()
        maskMarkdown(masked)
        val matches = mutableListOf<MathsMatch>()
        for (kind in 0..3) {
            val display = kind % 2 == 0
            val latex = kind >= 2
            val closings = if (latex) latexClosings(masked, display) else null
            var cursor = 0
            var index = 0
            while (index < masked.size - 1) {
                val context = if (display) {
                    isLineStart(masked, index)
                } else if (isLineStart(masked, index)) {
                    true
                } else {
                    index > cursor && if (latex) masked[index - 1] != '\\' else isInlineContext(masked[index - 1])
                }
                val opener = if (latex) {
                    masked[index] == '\\' && masked[index + 1] == if (display) '[' else '('
                } else {
                    masked[index] == '$' && (!display || masked[index + 1] == '$')
                }
                val start = index + if (display || latex) 2 else 1
                val end = if (context && opener) {
                    if (latex) {
                        closings!![start].takeIf { it > start }
                    } else {
                        dollarClosing(masked, start, display)
                    }
                } else {
                    null
                }
                if (end == null) {
                    index++
                    continue
                }
                val until = end + if (display || latex) 2 else 1
                matches.add(MathsMatch(index until until, source.substring(start, end), display))
                masked.fill('\u0000', index, until)
                cursor = until
                index = until
            }
        }
        return matches.sortedBy { it.range.first }.take(limit)
    }

    private fun dollarClosing(text: CharArray, start: Int, display: Boolean): Int? {
        if (start >= text.size || !display && isRegexWhitespace(text[start])) return null
        var index = start
        var terminal = false
        while (index < text.size) {
            val char = text[index]
            if (char == '\u0000' || !display && char == '\n') return null
            if (char == '$') {
                return if (display) {
                    index.takeIf { it > start && isDisplayClosing(text, index) }
                } else {
                    index.takeIf { terminal && (index + 1 == text.size || text[index + 1] !in '0'..'9') }
                }
            }
            // In display mode the lazy pattern tests the closing delimiter before consuming an escaped dollar.
            if (char == '\\' && index + 1 < text.size && text[index + 1] == '$') {
                if (display && isDisplayClosing(text, index + 1)) return index + 1
                terminal = true
                index += 2
            } else {
                terminal = char != '\\' && !isRegexWhitespace(char)
                index++
            }
        }
        return null
    }

    private fun isDisplayClosing(text: CharArray, index: Int): Boolean =
        index + 1 < text.size && text[index + 1] == '$' && isLineEnd(text, index + 2)

    private fun latexClosings(text: CharArray, display: Boolean): IntArray {
        val result = IntArray(text.size + 1) { -1 }
        var closing = -1
        for (index in text.lastIndex downTo 0) {
            if (text[index] == '\u0000') closing = -1
            if (text[index] == '\\' && index + 1 < text.size && text[index + 1] == if (display) ']' else ')') {
                if (!display || isLineEnd(text, index + 2)) closing = index
            }
            result[index] = closing
        }
        return result
    }

    private fun isInlineContext(char: Char): Boolean = isRegexWhitespace(char) || char in ".,!?:;([{\"'"
    private fun isRegexWhitespace(char: Char): Boolean = char in " \t\n\u000B\u000C\r"
    private fun isLineStart(text: CharArray, index: Int): Boolean = index == 0 ||
        text[index - 1] in "\n\u0085\u2028\u2029" || text[index - 1] == '\r' && text[index] != '\n'
    private fun isLineEnd(text: CharArray, index: Int): Boolean = index == text.size ||
        text[index] in "\r\u0085\u2028\u2029" || text[index] == '\n' && (index == 0 || text[index - 1] != '\r')
}
