/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.ui.graph

import androidx.compose.runtime.Immutable
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiBadgeTone
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** Ported from element-web packages/shared-components/src/room/timeline/event-tile/body/InteractiveUiView/graph/GraphLayout.ts
 * at bcb08017 (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial).
 */
data class InteractiveUiGraphInput(val topToBottom: Boolean, val layers: List<String>, val nodes: List<Node>, val edges: List<Edge>) {
    data class Node(val id: String, val label: String, val detail: String?, val layer: Int, val tone: InteractiveUiBadgeTone)
    data class Edge(val from: String, val to: String, val label: String?, val dashed: Boolean)
}

@Immutable
data class InteractiveUiGraphLayout(
    val topToBottom: Boolean,
    val layers: ImmutableList<String>,
    val nodes: ImmutableList<Node>,
    val edges: ImmutableList<InteractiveUiGraphInput.Edge>,
) {
    data class Node(val id: String, val label: String, val detail: String?, val tone: InteractiveUiBadgeTone, val layer: Int, val order: Int)
}

/** Four stable barycentre sweeps (down, up, down, up), retaining disconnected node order. */
fun layoutLayeredGraph(input: InteractiveUiGraphInput): InteractiveUiGraphLayout {
    val groups = input.layers.indices.map { layer -> input.nodes.filter { it.layer == layer }.toMutableList() }
    val byId = input.nodes.associateBy { it.id }
    val orders = mutableMapOf<String, Int>()
    fun setOrders() = groups.forEach { group -> group.forEachIndexed { order, node -> orders[node.id] = order } }
    setOrders()
    for (down in listOf(true, false, true, false)) {
        val indices = if (down) groups.indices.toList() else groups.indices.reversed().toList()
        for (index in indices) {
            val adjacent = index + if (down) -1 else 1
            if (adjacent !in groups.indices) continue
            val scores = groups[index].associate { node ->
                val neighbours = input.edges.mapNotNull { edge ->
                    val other = when (node.id) {
                        edge.from -> byId[edge.to]
                        edge.to -> byId[edge.from]
                        else -> null
                    }
                    other?.takeIf { it.layer == adjacent }?.let { orders.getValue(it.id) }
                }
                node.id to if (neighbours.isEmpty()) orders.getValue(node.id).toDouble() else neighbours.average()
            }
            groups[index].sortWith(compareBy({ scores.getValue(it.id) }, { orders.getValue(it.id) }))
            setOrders()
        }
    }
    return InteractiveUiGraphLayout(
        input.topToBottom,
        input.layers.toImmutableList(),
        groups.flatten().map { InteractiveUiGraphLayout.Node(it.id, it.label, it.detail, it.tone, it.layer, orders.getValue(it.id)) }.toImmutableList(),
        input.edges.toImmutableList(),
    )
}
