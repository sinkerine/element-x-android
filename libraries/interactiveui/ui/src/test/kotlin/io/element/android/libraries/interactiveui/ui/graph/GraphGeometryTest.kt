/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.ui.graph

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.google.common.truth.Truth.assertThat
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Test

class GraphGeometryTest : RobolectricTest() {
    @Test
    fun `six steps uses Web fixed dp dimensions`() {
        val input = graphFixtures("six-steps").single()
        val geometry = graphGeometry(layoutLayeredGraph(input))
        assertThat(geometry.width).isEqualTo(1464f)
        assertThat(geometry.height).isEqualTo(152f)
        assertThat(geometry.boxes["step1"]).isEqualTo(Rect(48f, 48f, 216f, 104f))
        assertThat(geometry.boxes["step6"]).isEqualTo(Rect(1248f, 48f, 1416f, 104f))
        assertThat(geometry.paths.first().segments.single().c1).isEqualTo(Offset(252f, 76f))
        val vertical = graphGeometry(layoutLayeredGraph(input.copy(topToBottom = true)))
        assertThat(vertical.width).isEqualTo(264f)
        assertThat(vertical.height).isEqualTo(792f)
    }

    @Test
    fun `labelled skip backward same-layer and colliding edges have distinct outside lanes`() {
        for (vertical in listOf(false, true)) {
            val input = graphFixtures("knowledge-map.en").first()
            val a = input.nodes.first().id
            val b = input.nodes.last().id
            val extra = listOf(a to b, b to a, a to a, a to b).mapIndexed { i, (from, to) ->
                InteractiveUiGraphInput.Edge(from, to, "Relationship $i", true)
            }
            val geometry = graphGeometry(layoutLayeredGraph(input.copy(topToBottom = vertical, edges = extra)))
            val labels = geometry.paths.map { it.labelBox }
            labels.forEachIndexed { i, box ->
                assertThat(geometry.boxes.values.any { it.overlaps(box) }).isFalse()
                assertThat(labels.drop(i + 1).any { it.overlaps(box) }).isFalse()
                assertThat(box.right + 8).isAtMost(geometry.width)
                assertThat(box.bottom + 8).isAtMost(geometry.height)
            }
            assertThat(geometry.paths.all { it.segments.size == 5 }).isTrue()
        }
    }

    @Test
    fun `converging labels relocate the entire path outside`() {
        val input = crossingInput().copy(
            edges = listOf(InteractiveUiGraphInput.Edge("a", "c", "One", false), InteractiveUiGraphInput.Edge("a", "c", "Two", true))
        )
        val geometry = graphGeometry(layoutLayeredGraph(input))
        assertThat(geometry.paths[0].segments).hasSize(1)
        assertThat(geometry.paths[1].segments).hasSize(5)
        assertThat(geometry.paths[0].labelBox.overlaps(geometry.paths[1].labelBox)).isFalse()
    }
}
