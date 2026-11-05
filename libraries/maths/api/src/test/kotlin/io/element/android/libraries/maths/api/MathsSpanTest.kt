/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.maths.api

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.SpannableString
import android.text.Spanned
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.maths.test.FakeMathsLayout
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Test
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MathsSpanTest : RobolectricTest() {
    private val paint = Paint().apply {
        textSize = 20f
        color = Color.RED
    }

    @Test
    fun `natural size rounds up and preserves paint metrics for small maths`() {
        val span = MathsSpan("x", FakeMathsLayout(widthEm = 2.01f, heightEm = 0.1f, depthEm = 0.1f), false)
        val metrics = Paint.FontMetricsInt()
        assertThat(span.getSize(paint, null, 0, 1, metrics)).isEqualTo(41)
        val original = paint.fontMetricsInt
        assertThat(listOf(metrics.ascent, metrics.top, metrics.descent, metrics.bottom))
            .containsExactly(original.ascent, original.top, original.descent, original.bottom).inOrder()
    }

    @Test
    fun `metrics expand to enclose tall maths`() {
        val span = MathsSpan("x", FakeMathsLayout(heightEm = 3.01f, depthEm = 2.01f), true)
        val metrics = Paint.FontMetricsInt()
        span.getSize(paint, null, 0, 1, metrics)
        assertThat(metrics.ascent).isEqualTo(-61)
        assertThat(metrics.top).isAtMost(metrics.ascent)
        assertThat(metrics.descent).isEqualTo(41)
        assertThat(metrics.bottom).isAtLeast(metrics.descent)
    }

    @Test
    fun `fit scale shrinks to width with minimum of forty percent`() {
        listOf(100 to 40, 30 to 30, 5 to 16).forEach { (maxWidth, expected) ->
            val span = MathsSpan("x", FakeMathsLayout(), false, maxWidth)
            assertThat(span.getSize(paint, null, 0, 1, null)).isEqualTo(expected)
        }
    }

    @Test
    fun `draw uses scaled font size current colour and baseline translation and restores canvas`() {
        val layout = FakeMathsLayout()
        val span = MathsSpan("x", layout, false, 20)
        val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        span.draw(canvas, null, 0, 1, 10f, 0, 30, 80, paint)
        assertThat(layout.drawCalls).containsExactly(10f to Color.RED)
        assertThat(bitmap.getPixel(10, 23)).isEqualTo(Color.RED)
        assertThat(bitmap.getPixel(9, 23)).isEqualTo(Color.TRANSPARENT)
        assertThat(bitmap.getPixel(10, 21)).isEqualTo(Color.TRANSPARENT)
        canvas.drawRect(0f, 0f, 1f, 1f, paint)
        assertThat(bitmap.getPixel(0, 0)).isEqualTo(Color.RED)
    }

    @Test
    fun `width helper preserves ordinary instances and replaces every maths span without mutating original`() {
        val plain = SpannableString("plain")
        assertThat(plain.containsMathsSpans()).isFalse()
        assertThat(plain.withMathsMaxWidth(10)).isSameInstanceAs(plain)
        assertThat("plain".withMathsMaxWidth(10)).isSameInstanceAs("plain")
        val text = SpannableString("a\uFFFCb\uFFFC")
        val first = MathsSpan("x", FakeMathsLayout(), false)
        val second = MathsSpan("y", FakeMathsLayout(), true)
        text.setSpan(first, 1, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(second, 3, 4, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        val style = Any()
        text.setSpan(style, 0, 4, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        val result = text.withMathsMaxWidth(15) as Spanned
        assertThat(result.containsMathsSpans()).isTrue()
        assertThat(result.toString()).isEqualTo(text.toString())
        val spans = result.getSpans(0, result.length, MathsSpan::class.java)
        assertThat(spans.map { it.maxWidthPx }).containsExactly(15, 15)
        assertThat(spans.map { it.tex }).containsExactly("x", "y").inOrder()
        spans.forEachIndexed { index, span ->
            val original = listOf(first, second)[index]
            assertThat(span).isNotSameInstanceAs(original)
            assertThat(span.layout).isSameInstanceAs(original.layout)
            assertThat(span.displayMode).isEqualTo(original.displayMode)
            assertThat(result.getSpanStart(span)).isEqualTo(text.getSpanStart(original))
            assertThat(result.getSpanEnd(span)).isEqualTo(text.getSpanEnd(original))
            assertThat(result.getSpanFlags(span)).isEqualTo(text.getSpanFlags(original))
        }
        assertThat(result.getSpanStart(style)).isEqualTo(0)
        assertThat(text.getSpans(0, 4, MathsSpan::class.java).toList()).containsExactly(first, second)
        assertThat(first.maxWidthPx).isEqualTo(Int.MAX_VALUE)
    }
}
