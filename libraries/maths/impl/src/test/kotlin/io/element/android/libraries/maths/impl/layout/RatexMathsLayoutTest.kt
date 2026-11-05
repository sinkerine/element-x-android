/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.maths.impl.layout

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.ratex.DisplayItem
import io.ratex.DisplayList
import io.ratex.RaTeXColor
import org.junit.Test
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RatexMathsLayoutTest : RobolectricTest() {
    @Test
    fun `draw uses origin without bleed offset and changes default colour between draws`() {
        val sentinel = RaTeXColor(31 / 255f, 116 / 255f, 201 / 255f, 1f)
        val layout = RatexMathsLayout(DisplayList(2.0, 0.75, 0.25, listOf(
            DisplayItem.Rect(0.0, 0.0, 1.0, 1.0, sentinel),
            DisplayItem.Rect(1.0, 0.0, 1.0, 1.0, RaTeXColor(1f, 0f, 0f, 1f)),
        )))
        assertThat(layout.widthEm).isEqualTo(2f)
        assertThat(layout.heightEm).isEqualTo(0.75f)
        assertThat(layout.depthEm).isEqualTo(0.25f)
        listOf(Color.WHITE, Color.BLACK, Color.BLACK).forEach { color ->
            val bitmap = Bitmap.createBitmap(30, 20, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            layout.draw(canvas, 10f, color)
            assertThat(bitmap.getPixel(0, 0)).isEqualTo(color)
            assertThat(bitmap.getPixel(9, 9)).isEqualTo(color)
            assertThat(bitmap.getPixel(10, 0)).isEqualTo(Color.RED)
            assertThat(bitmap.getPixel(0, 10)).isEqualTo(Color.TRANSPARENT)
        }
    }
}
