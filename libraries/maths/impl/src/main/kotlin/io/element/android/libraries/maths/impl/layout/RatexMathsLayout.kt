/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.maths.impl.layout

import android.graphics.Canvas
import androidx.core.graphics.withTranslation
import io.element.android.libraries.maths.api.MathsLayout
import io.ratex.DisplayList
import io.ratex.RaTeXFontLoader
import io.ratex.RaTeXRenderer
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

internal class RatexMathsLayout(private val displayList: DisplayList) : MathsLayout {
    override val widthEm: Float = displayList.width.toFloat()
    override val heightEm: Float = displayList.height.toFloat()
    override val depthEm: Float = displayList.depth.toFloat()
    @Volatile private var colored: Pair<Int, DisplayList>? = null
    private val drawFailureLogged = AtomicBoolean(false)

    override fun draw(canvas: Canvas, fontSizePx: Float, color: Int) {
        try {
            val dl = colored?.takeIf { it.first == color }?.second
                ?: displayList.withDefaultColor(color).also { colored = color to it }
            val renderer = RaTeXRenderer(dl, fontSizePx) { RaTeXFontLoader.getTypeface(it) }
            canvas.withTranslation(0f, -renderer.glyphVerticalBleedPx) { renderer.draw(this) }
        } catch (error: Exception) {
            if (drawFailureLogged.compareAndSet(false, true)) Timber.w("Maths drawing failed (%s)", error.javaClass.simpleName)
        }
    }
}
