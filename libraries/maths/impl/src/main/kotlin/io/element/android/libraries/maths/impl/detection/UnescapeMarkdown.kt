/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.maths.impl.detection

import org.jsoup.nodes.Entities
import org.jsoup.parser.Parser

/** CommonMark escapes only ASCII punctuation, including backslash itself. */
internal fun unescapeMarkdown(text: String): String = buildString(text.length) {
    var index = 0
    while (index < text.length) {
        if (text[index] == '\\' && index + 1 < text.length && text[index + 1].isAsciiPunctuation()) index++
        append(text[index++])
    }
}

private fun Char.isAsciiPunctuation(): Boolean = this in '!'..'/' || this in ':'..'@' || this in '['..'`' || this in '{'..'~'

/** Resolves escapes and character references once, in source order, as CommonMark visible text. */
internal fun normalizeMarkdownText(text: String, budget: MathsSearchBudget): String = buildString(text.length) {
    var index = 0
    while (index < text.length) {
        budget.spend()
        val entity = if (text[index] == '&') markdownEntity.matchAt(text, index) else null
        if (text[index] == '\\' && index + 1 < text.length && text[index + 1].isAsciiPunctuation()) {
            append(text[index + 1])
            index += 2
        } else if (entity != null) {
            val reference = entity.value
            // HTML decoding accepts known prefixes of unknown names; CommonMark requires the complete name.
            val recognized = reference[1] == '#' || Entities.isNamedEntity(reference.substring(1, reference.lastIndex))
            append(if (recognized) Parser.unescapeEntities(reference, false) else reference)
            index += entity.value.length
        } else {
            append(text[index++])
        }
    }
}

private val markdownEntity = Regex("&(?:#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});")
