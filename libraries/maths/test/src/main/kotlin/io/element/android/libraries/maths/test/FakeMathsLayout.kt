/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.maths.test

import android.graphics.Canvas
import android.graphics.Paint
import io.element.android.libraries.maths.api.MathsLayout

class FakeMathsLayout(
    override val widthEm: Float = 2f,
    override val heightEm: Float = 0.75f,
    override val depthEm: Float = 0.25f,
) : MathsLayout {
    private val recordedDrawCalls = mutableListOf<Pair<Float, Int>>()
    val drawCalls: List<Pair<Float, Int>> get() = recordedDrawCalls.toList()

    override fun draw(canvas: Canvas, fontSizePx: Float, color: Int) {
        recordedDrawCalls += fontSizePx to color
        canvas.drawRect(0f, 0f, widthEm * fontSizePx, (heightEm + depthEm) * fontSizePx, Paint().apply { this.color = color })
    }
}
