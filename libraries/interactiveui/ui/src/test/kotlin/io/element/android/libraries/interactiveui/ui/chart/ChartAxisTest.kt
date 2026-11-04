/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.ui.chart

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.pow
import kotlin.random.Random

class ChartAxisTest {
    @Test
    fun `minimum plot width reserves adjacent half widths and gap at normalized tick distances`() {
        val axis = ChartAxis(listOf(0.0, 100.0))
        val widths = listOf(20, 100, 200, 10, 40, 60)
        val width = axis.minimumPlotWidth(widths, 8f)
        assertThat(width).isWithin(1f).of(790f)
        axis.ticks.zipWithNext().forEachIndexed { index, (first, second) ->
            val distance = (axis.position(second) - axis.position(first)) * width
            assertThat(distance).isAtLeast((widths[index] + widths[index + 1]) / 2f + 8f - 0.001f)
        }
    }

    @Test
    fun `finite ranges produce bounded plot widths including zero constant extreme and subnormal data`() {
        val random = Random(42)
        val ranges = listOf(
            0.0 to 0.0,
            7.0 to 7.0,
            -7.0 to -7.0,
            -1e20 to 1e20,
            -Double.MAX_VALUE to Double.MAX_VALUE,
            0.0 to Double.MIN_VALUE,
            -Double.MIN_VALUE to Double.MIN_VALUE,
            Double.MIN_VALUE to 8 * Double.MIN_VALUE,
            1.0 to Math.nextUp(1.0),
        ) + List(2000) {
            val magnitude = 10.0.pow(random.nextInt(-323, 309))
            magnitude * random.nextDouble(-1.0, 1.0) to magnitude * random.nextDouble(-1.0, 1.0)
        }
        ranges.forEach { (first, second) ->
            val axis = ChartAxis(listOf(first, second))
            val widths = axis.ticks.map { random.nextInt(1, 1000) }
            val width = axis.minimumPlotWidth(widths, 16f)
            assertThat(width.isFinite()).isTrue()
            assertThat(width).isAtLeast(0f)
            assertThat(width).isLessThan(20_000f)
            axis.ticks.zipWithNext().forEachIndexed { index, (left, right) ->
                val separation = (axis.position(right) - axis.position(left)) * width
                assertThat(separation).isAtLeast((widths[index] + widths[index + 1]) / 2f + 16f - 0.01f)
            }
        }
    }
}
