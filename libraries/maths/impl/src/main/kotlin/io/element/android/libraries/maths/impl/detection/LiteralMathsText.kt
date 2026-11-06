/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.maths.impl.detection

/** A literal whitespace-flexible match; offsets refer to the original visible text. */
internal fun findMathsText(text: String, needle: String, budget: MathsSearchBudget, startIndex: Int = 0): IntRange? {
    for (start in startIndex until text.length) {
        var index = start
        var part = 0
        var matches = true
        while (matches && index < text.length && part < needle.length) {
            budget.spend()
            if (needle[part].isMathsWhitespace()) {
                matches = text[index].isMathsWhitespace()
                while (part < needle.length && needle[part].isMathsWhitespace()) {
                    budget.spend()
                    part++
                }
                while (matches && index < text.length && text[index].isMathsWhitespace()) {
                    budget.spend()
                    index++
                }
            } else {
                matches = text[index] == needle[part]
                if (matches) {
                    index++
                    part++
                }
            }
        }
        if (matches && part == needle.length) return start until index
    }
    return null
}

/** Canonical key for exactly the whitespace equivalence used by [findMathsText]. */
internal fun mathsTextKey(text: String, budget: MathsSearchBudget): String = buildString {
    text.forEach { char ->
        budget.spend()
        if (!char.isMathsWhitespace()) {
            append(char)
        } else if (isEmpty() || last() != ' ') {
            append(' ')
        }
    }
}

private fun Char.isMathsWhitespace(): Boolean = this in " \t\n\u000B\u000C\r"
