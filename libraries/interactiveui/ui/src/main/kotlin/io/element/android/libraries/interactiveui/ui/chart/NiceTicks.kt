/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.ui.chart

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Ported from element-web packages/shared-components/src/room/timeline/event-tile/body/InteractiveUiView/chart/niceTicks.ts
 * at bcb08017 (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial).
 * Normalize before subtraction, and construct decimal steps before converting subnormal values.
 */
fun niceTicks(min: Double, max: Double, maxTicks: Int): List<Double> {
    if (!min.isFinite() || !max.isFinite() || maxTicks < 1) return emptyList()
    val lo = minOf(min, max)
    val hi = maxOf(min, max)
    val limit = maxTicks.coerceIn(1, 1000)
    if (lo == hi || limit == 1) return listOf(if (lo == 0.0) 0.0 else lo)
    val scale = maxOf(abs(lo), abs(hi))
    val span = hi / scale - lo / scale
    val logStep = log10(span) + log10(scale) - log10(limit - 1.0)
    val exponent = floor(logStep).toInt()
    val ratio = BigDecimal.valueOf(10.0.pow(logStep - exponent)).round(MathContext(14)).toDouble()
    val factor = when {
        ratio <= 1 -> 1
        ratio <= 2 -> 2
        ratio <= 5 -> 5
        else -> 10
    }
    val step = "${factor}e$exponent".toDouble()
    if (!step.isFinite() || step == 0.0) return listOf(lo, hi).take(limit)
    val first = ceil(lo / step)
    val last = floor(hi / step)
    val ticks = mutableListOf<Double>()
    for (offset in 0 until limit) {
        val index = first + offset
        if (index <= last) {
            val raw = index * step
            if (raw.isFinite()) {
                val value = BigDecimal.valueOf(raw).round(MathContext(14)).toDouble()
                val inBounds = value >= lo && value <= hi
                val increasing = ticks.isEmpty() || value > ticks.last()
                if (inBounds && increasing) ticks.add(if (value == 0.0) 0.0 else value)
            }
        }
    }
    return ticks.ifEmpty { listOf(lo, hi).take(limit) }
}
