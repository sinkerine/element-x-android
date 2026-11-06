/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.maths.impl.layout

import com.google.common.truth.Truth.assertThat
import io.ratex.DisplayItem
import io.ratex.DisplayList
import io.ratex.RaTeXColor
import org.junit.Test

class DisplayListRecolorTest {
    private val sentinel = RaTeXColor(31 / 255f, 116 / 255f, 201 / 255f, 1f)
    private val explicit = RaTeXColor(1f, 0f, 0f, 1f)
    private val replacement = RaTeXColor(0f, 1f, 0f, 128 / 255f)

    private fun items(color: RaTeXColor): List<DisplayItem> = listOf(
        DisplayItem.GlyphPath(1.0, 2.0, 1.0, "Main-Regular", 120, color = color),
        DisplayItem.Line(1.0, 2.0, 3.0, 0.1, color, dashed = true),
        DisplayItem.Rect(1.0, 2.0, 3.0, 4.0, color),
        DisplayItem.Path(1.0, 2.0, emptyList(), true, color),
    )

    @Test
    fun `recolours every item type without changing explicit colours geometry or original`() {
        val original = DisplayList(3.0, 2.0, 1.0, items(sentinel) + items(explicit))
        assertThat(original.withDefaultColor(0x8000FF00.toInt()))
            .isEqualTo(original.copy(items = items(replacement) + items(explicit)))
        assertThat(original.items).containsExactlyElementsIn(items(sentinel) + items(explicit)).inOrder()
    }

    @Test
    fun `matches sentinel within half a channel step including alpha`() {
        val near = sentinel.copy(r = sentinel.r + 0.49f / 255, a = 1f - 0.49f / 255)
        val far = sentinel.copy(b = sentinel.b + 0.51f / 255)
        val alpha = sentinel.copy(a = 1f - 0.51f / 255)
        val original = DisplayList(3.0, 2.0, 1.0, items(near) + items(far) + items(alpha))
        assertThat(original.withDefaultColor(0x8000FF00.toInt()).items)
            .containsExactlyElementsIn(items(replacement) + items(far) + items(alpha))
            .inOrder()
    }
}
