/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.maths.api

import android.graphics.Canvas
import androidx.annotation.ColorInt

/** Dimensions are in em. The baseline is [heightEm] below the top; total height is [heightEm] + [depthEm]. */
interface MathsLayout {
    val widthEm: Float
    val heightEm: Float
    val depthEm: Float

    /** Draws at the canvas origin, using [color] for parts without an explicit TeX colour. Never throws. */
    fun draw(canvas: Canvas, fontSizePx: Float, @ColorInt color: Int)
}
