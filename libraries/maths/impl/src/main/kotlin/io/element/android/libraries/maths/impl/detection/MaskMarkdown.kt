/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.maths.impl.detection

/** Masks code and explicit Markdown links without changing source offsets. All indexing passes are linear. */
internal fun maskMarkdown(text: CharArray) {
    maskFences(text)
    val fences = BooleanArray(text.size) { text[it] == '\u0000' }
    maskSpans(text)
    maskLinks(text, fences)
}

private fun maskFences(text: CharArray) {
    var line = 0
    var openingLength = 0
    while (line < text.size) {
        var end = line
        while (end < text.size && text[end] != '\n' && text[end] != '\r') end++
        var next = end
        if (next < text.size && text[next] == '\r') next++
        if (next < text.size && text[next] == '\n') next++
        var start = line
        while (start < end && text[start] == ' ') start++
        var runEnd = start
        while (runEnd < end && text[runEnd] == '`') runEnd++
        val length = runEnd - start
        val indented = start - line <= 3
        if (openingLength > 0) {
            val closes = indented && length >= openingLength && (runEnd until end).all { text[it] == ' ' || text[it] == '\t' }
            text.fill('\u0000', line, end)
            if (closes) openingLength = 0
        } else if (indented && length >= 3 && (runEnd until end).none { text[it] == '`' }) {
            openingLength = length
            text.fill('\u0000', line, end)
        }
        line = next
    }
}

private fun escapedCharacters(text: CharArray): BooleanArray {
    val escaped = BooleanArray(text.size)
    var backslashes = 0
    text.indices.forEach { index ->
        escaped[index] = backslashes % 2 == 1
        backslashes = if (text[index] == '\\') backslashes + 1 else 0
    }
    return escaped
}

private fun maskSpans(text: CharArray) {
    val escaped = escapedCharacters(text)
    val runs = mutableListOf<IntRange>()
    var index = 0
    while (index < text.size) {
        if (text[index] != '`') {
            index++
            continue
        }
        val start = index
        while (index < text.size && text[index] == '`') index++
        runs.add(start until index)
    }
    val nextByLength = mutableMapOf<Int, Int>()
    val next = IntArray(runs.size) { -1 }
    // Fence masks are barriers even for spans with equal-length backticks on opposite sides.
    var boundary = text.size
    for (run in runs.indices.reversed()) {
        if ((runs[run].last + 1 until boundary).any { text[it] == '\u0000' }) nextByLength.clear()
        val openingLength = runs[run].count() - if (escaped[runs[run].first]) 1 else 0
        next[run] = nextByLength[openingLength] ?: -1
        nextByLength[runs[run].count()] = run
        boundary = runs[run].first
    }
    var run = 0
    while (run < runs.size) {
        val closing = next[run]
        if (closing < 0) {
            run++
        } else {
            text.fill('\u0000', runs[run].first + if (escaped[runs[run].first]) 1 else 0, runs[closing].last + 1)
            run = closing + 1
        }
    }
}

private fun maskLinks(text: CharArray, fences: BooleanArray) {
    val escaped = escapedCharacters(text)
    val parentheses = ArrayDeque<Int>()
    val brackets = ArrayDeque<Int>()
    val closing = IntArray(text.size) { -1 }
    for (index in text.indices) {
        if (fences[index]) {
            parentheses.clear()
            brackets.clear()
        } else if (!escaped[index]) {
            when (text[index]) {
                '(' -> parentheses.add(index)
                ')' -> if (parentheses.isNotEmpty()) closing[parentheses.removeLast()] = index
                '[' -> brackets.add(index)
                ']' -> if (brackets.isNotEmpty()) closing[brackets.removeLast()] = index
            }
        }
    }
    var index = 0
    while (index < text.size) {
        val labelEnd = closing[index]
        val target = labelEnd + 1
        val hasTarget = labelEnd >= 0 && target < text.size && text[target] in "([" && closing[target] >= 0
        if (text[index] == '[' && hasTarget && !escaped[index]) {
            val end = closing[target] + 1
            text.fill('\u0000', index, end)
            index = end
        } else {
            index++
        }
    }
}
