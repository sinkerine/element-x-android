/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.maths.api

import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import androidx.core.graphics.withTranslation
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Draws [layout] on the text baseline, scaling to [maxWidthPx], and expands line metrics as needed. */
class MathsSpan(
    val tex: String,
    val layout: MathsLayout,
    val displayMode: Boolean,
    val maxWidthPx: Int = Int.MAX_VALUE,
) : ReplacementSpan() {
    private fun fontSize(paint: Paint): Float {
        val natural = layout.widthEm * paint.textSize
        val scale = if (natural > maxWidthPx) max(MIN_FIT_SCALE, maxWidthPx / natural) else 1f
        return paint.textSize * scale
    }

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val fs = fontSize(paint)
        if (fm != null) {
            paint.getFontMetricsInt(fm)
            fm.ascent = min(fm.ascent, -ceil(layout.heightEm * fs).toInt())
            fm.top = min(fm.top, fm.ascent)
            fm.descent = max(fm.descent, ceil(layout.depthEm * fs).toInt())
            fm.bottom = max(fm.bottom, fm.descent)
        }
        return ceil(layout.widthEm * fs).toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val fs = fontSize(paint)
        canvas.withTranslation(x, y - layout.heightEm * fs) { layout.draw(this, fs, paint.color) }
    }

    companion object {
        const val MIN_FIT_SCALE = 0.4f
    }
}

/** Returns a copy with every maths span limited to [maxWidthPx], or this instance when there are none. */
fun CharSequence.withMathsMaxWidth(maxWidthPx: Int): CharSequence {
    if (!containsMathsSpans()) return this
    return SpannableStringBuilder(this).apply {
        getSpans(0, length, MathsSpan::class.java).forEach { span ->
            val start = getSpanStart(span)
            val end = getSpanEnd(span)
            val flags = getSpanFlags(span)
            removeSpan(span)
            setSpan(MathsSpan(span.tex, span.layout, span.displayMode, maxWidthPx), start, end, flags)
        }
    }
}

/** True when this is spanned text containing maths. */
fun CharSequence.containsMathsSpans(): Boolean = this is Spanned && getSpans(0, length, MathsSpan::class.java).isNotEmpty()
