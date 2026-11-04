/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.ui.graph

import com.google.common.truth.Truth.assertThat
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Test

class LayeredGraphLayoutTest : RobolectricTest() {
    @Test
    fun `crossing is removed with stable ties and no input mutation`() {
        val input = crossingInput()
        val before = input.copy(nodes = input.nodes.toList(), edges = input.edges.toList())
        val layout = layoutLayeredGraph(input)
        assertThat(layoutLayeredGraph(input)).isEqualTo(layout)
        assertThat(input).isEqualTo(before)
        assertThat(layout.nodes.filter { it.layer == 1 }.map { it.id }).containsExactly("d", "c", "e").inOrder()
        assertThat(crossings(layout.nodes.associate { it.id to (it.layer to it.order) }, input.edges)).isEqualTo(0)
    }

    @Test
    fun `all graph fixtures are deterministic and crossings never increase`() {
        listOf("six-steps", "knowledge-map.en", "knowledge-map.zh").flatMap(::graphFixtures).forEach { input ->
            val layout = layoutLayeredGraph(input)
            assertThat(layoutLayeredGraph(input)).isEqualTo(layout)
            val original = input.nodes.groupBy { it.layer }.flatMap { (_, group) -> group.mapIndexed { i, node -> node.id to (node.layer to i) } }.toMap()
            val ordered = layout.nodes.associate { it.id to (it.layer to it.order) }
            assertThat(crossings(ordered, input.edges)).isAtMost(crossings(original, input.edges))
        }
    }

    @Test
    fun `four sweeps propagate ordering back through multiple layers`() {
        // Expected order captured by executing the Web implementation at bcb08017.
        // Two sweeps leave n9 before n10; the second down/up pair puts n10 first.
        val input = InteractiveUiGraphInput(
            false,
            listOf("A", "B", "C", "D"),
            (0..11).map { index -> crossingInput().nodes.first().copy(id = "n$index", layer = index / 3) },
            listOf(2 to 4, 2 to 5, 3 to 8, 4 to 6, 6 to 9, 6 to 11, 7 to 9, 7 to 10, 8 to 9, 8 to 10)
                .map { (from, to) -> InteractiveUiGraphInput.Edge("n$from", "n$to", null, false) },
        )
        assertThat(layoutLayeredGraph(input).nodes.map { it.id })
            .containsExactly("n0", "n1", "n2", "n3", "n4", "n5", "n8", "n7", "n6", "n10", "n9", "n11")
            .inOrder()
        val disconnected = crossingInput().copy(edges = emptyList())
        assertThat(layoutLayeredGraph(disconnected).nodes.map { it.id }).containsExactly("a", "b", "c", "d", "e").inOrder()
    }

    private fun crossings(nodes: Map<String, Pair<Int, Int>>, edges: List<InteractiveUiGraphInput.Edge>): Int {
        var count = 0
        edges.forEachIndexed { i, edge ->
            edges.drop(i + 1).forEach { other ->
            val a = nodes.getValue(edge.from)
            val b = nodes.getValue(edge.to)
            val c = nodes.getValue(other.from)
            val d = nodes.getValue(other.to)
            if (a.first == c.first && b.first == d.first && (a.second - c.second) * (b.second - d.second) < 0) count++
        }
        }
        return count
    }
}
