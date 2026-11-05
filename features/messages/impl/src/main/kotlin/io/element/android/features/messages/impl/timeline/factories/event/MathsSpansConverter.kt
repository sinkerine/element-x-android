/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.factories.event

import android.text.SpannableStringBuilder
import android.text.Spanned
import dev.zacsweers.metro.Inject
import io.element.android.libraries.maths.api.MAX_MATHS_ELEMENTS_PER_MESSAGE
import io.element.android.libraries.maths.api.MathsLayoutEngine
import io.element.android.libraries.maths.api.MathsSpan
import io.element.android.libraries.maths.api.isDisplayMaths
import io.element.android.libraries.maths.api.isMathsElement
import io.element.android.libraries.maths.api.mathsTex
import io.element.android.libraries.maths.api.replaceMathsWithFallback
import org.jsoup.nodes.Document
import org.jsoup.nodes.TextNode
import timber.log.Timber

@Inject
class MathsSpansConverter(
    private val mathsLayoutEngine: MathsLayoutEngine,
) {
    /** Converts a render-only clone, preserving each element's children when maths cannot be rendered. */
    fun convert(document: Document, toSpans: (Document) -> CharSequence): CharSequence {
        if (document.selectFirst("span[data-mx-maths], div[data-mx-maths]") == null) return toSpans(document)
        val copy = document.clone()
        val elements = copy.getAllElements().filter { it.isMathsElement() }
        if (copy.text().contains(MATHS_MARKER)) {
            elements.forEach { it.replaceMathsWithFallback() }
            return toSpans(copy)
        }
        val spans = mutableListOf<MathsSpan>()
        elements.forEachIndexed { index, element ->
            val tex = element.mathsTex()
            val display = element.isDisplayMaths()
            val layout = if (index < MAX_MATHS_ELEMENTS_PER_MESSAGE) mathsLayoutEngine.layout(tex, display) else null
            if (layout == null) {
                element.replaceMathsWithFallback()
            } else {
                val marker = TextNode(MATHS_MARKER.toString())
                element.replaceWith(if (display) copy.createElement("p").appendChild(marker) else marker)
                spans.add(MathsSpan(tex, layout, display))
            }
        }
        val converted = toSpans(copy)
        val markerCount = converted.count { it == MATHS_MARKER }
        if (markerCount != spans.size) {
            Timber.w("Maths marker mismatch (expected=%d, actual=%d)", spans.size, markerCount)
            val fallback = document.clone()
            fallback.getAllElements().filter { it.isMathsElement() }.forEach { it.replaceMathsWithFallback() }
            return toSpans(fallback)
        }
        return SpannableStringBuilder(converted).apply {
            var spanIndex = 0
            converted.forEachIndexed { index, char ->
                if (char == MATHS_MARKER) setSpan(spans[spanIndex++], index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}

private const val MATHS_MARKER = '\uFFFC'
