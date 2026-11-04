/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.ui.chart

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NiceTicksTest {
    @Test
    fun `readable decimal ticks match Web reference values`() {
        assertThat(niceTicks(0.0, 7.0, 6)).containsExactly(0.0, 2.0, 4.0, 6.0).inOrder()
        assertThat(niceTicks(-7.0, 7.0, 6)).containsExactly(-5.0, 0.0, 5.0).inOrder()
        assertThat(niceTicks(0.0, 0.7, 6)).containsExactly(0.0, 0.2, 0.4, 0.6).inOrder()
        assertThat(niceTicks(3.0, 17.0, 6)).containsExactly(5.0, 10.0, 15.0).inOrder()
        assertThat(niceTicks(0.0, 100.0, 6)).containsExactly(0.0, 20.0, 40.0, 60.0, 80.0, 100.0).inOrder()
        assertThat(niceTicks(100.0, 0.0, 6)).isEqualTo(niceTicks(0.0, 100.0, 6))
    }

    @Test
    fun `extreme and subnormal ranges produce finite bounded increasing ticks`() {
        val ranges = listOf(
            0.0 to 0.0,
            7.0 to 7.0,
            -9.0 to -1.0,
            -10.0 to 10.0,
            -1e308 to 1e308,
            1e300 to 1.1e300,
            0.0 to Double.MIN_VALUE,
            1.0 to Math.nextUp(1.0),
            -Double.MAX_VALUE to Double.MAX_VALUE,
            Double.MIN_VALUE to 8 * Double.MIN_VALUE,
            1e-310 to 1.1e-310
        )
        ranges.forEach { (min, max) ->
            val ticks = niceTicks(min, max, 6)
            assertThat(ticks.size).isIn(1..6)
            ticks.forEach { assertThat(it.isFinite() && it >= min && it <= max).isTrue() }
            ticks.zipWithNext { a, b -> assertThat(a < b).isTrue() }
            val axis = ChartAxis(listOf(min, max))
            assertThat(axis.position(min).isFinite()).isTrue()
            assertThat(axis.position(max).isFinite()).isTrue()
        }
    }

    @Test
    fun `invalid bounds and budgets are safe`() {
        assertThat(niceTicks(Double.NaN, 1.0, 6)).isEmpty()
        assertThat(niceTicks(0.0, Double.POSITIVE_INFINITY, 6)).isEmpty()
        assertThat(niceTicks(0.0, 10.0, 0)).isEmpty()
        assertThat(niceTicks(0.0, 10.0, 1)).containsExactly(0.0)
        assertThat(niceTicks(0.0, 10_000.0, Int.MAX_VALUE).size).isAtMost(1000)
    }

    @Test
    fun `eight series retain unique non-colour encodings`() {
        assertThat((0..7).map(::seriesMarker).toSet()).hasSize(8)
        assertThat((0..7).map(::seriesDash).toSet()).hasSize(8)
    }
}
