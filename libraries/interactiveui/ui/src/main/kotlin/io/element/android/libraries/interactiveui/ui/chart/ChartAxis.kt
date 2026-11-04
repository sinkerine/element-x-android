/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.ui.chart

import kotlin.math.abs
import kotlin.math.ceil

/** Ported from element-web packages/shared-components/src/room/timeline/event-tile/body/InteractiveUiView/chart/InteractiveUiChartView.tsx
 * at bcb08017 (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial).
 */
internal class ChartAxis(values: List<Double>) {
    private val min = minOf(0.0, values.minOrNull() ?: 0.0)
    private val max = maxOf(0.0, values.maxOrNull() ?: 0.0).let { if (it == min) 1.0 else it }
    private val scale = maxOf(abs(min), abs(max))
    private val lo = min / scale
    private val span = max / scale - lo
    val ticks: List<Double> = niceTicks(min, max, 6)
    private fun normalizedPosition(value: Double): Double = (value / scale - lo) / span
    fun position(value: Double): Float = normalizedPosition(value).toFloat()

    fun minimumPlotWidth(tickWidths: List<Int>, gap: Float): Float = (1 until ticks.size).maxOfOrNull { index ->
        // Divide by normalized distances, rather than subtracting extreme or subnormal data values.
        val distance = normalizedPosition(ticks[index]) - normalizedPosition(ticks[index - 1])
        ceil(((tickWidths[index - 1].toDouble() + tickWidths[index]) / 2 + gap) / distance)
    }?.toFloat() ?: 0f
}
