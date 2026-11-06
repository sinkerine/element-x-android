/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.maths.impl.layout

import io.ratex.DisplayItem
import io.ratex.DisplayList
import io.ratex.RaTeXColor
import kotlin.math.abs

internal const val DEFAULT_COLOR_SENTINEL: Int = 0xFF1F74C9.toInt()

private fun Int.toRatexColor() = RaTeXColor(
    r = (this ushr 16 and 255) / 255f,
    g = (this ushr 8 and 255) / 255f,
    b = (this and 255) / 255f,
    a = (this ushr 24 and 255) / 255f,
)

/** Recolours only the sentinel, leaving explicit TeX colours intact. */
internal fun DisplayList.withDefaultColor(color: Int): DisplayList {
    val sentinel = DEFAULT_COLOR_SENTINEL.toRatexColor()
    val replacement = color.toRatexColor()
    fun RaTeXColor.recolor(): RaTeXColor {
        val matchesRgb = abs(r - sentinel.r) <= 0.5f / 255 && abs(g - sentinel.g) <= 0.5f / 255 && abs(b - sentinel.b) <= 0.5f / 255
        return if (matchesRgb && abs(a - sentinel.a) <= 0.5f / 255) {
            replacement
        } else {
            this
        }
    }
    return copy(items = items.map {
        when (it) {
            is DisplayItem.GlyphPath -> it.copy(color = it.color.recolor())
            is DisplayItem.Line -> it.copy(color = it.color.recolor())
            is DisplayItem.Rect -> it.copy(color = it.color.recolor())
            is DisplayItem.Path -> it.copy(color = it.color.recolor())
        }
    })
}
