/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.ui.graph

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/** Ported from element-web packages/shared-components/src/room/timeline/event-tile/body/InteractiveUiView/graph/graphGeometry.ts
 * at bcb08017 (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial).
 */
data class InteractiveUiGraphGeometry(
    val width: Float,
    val height: Float,
    val boxes: Map<String, Rect>,
    val headings: List<Pair<Offset, String>>,
    val paths: List<EdgePath>,
) {
    data class EdgePath(val segments: List<CubicSegment>, val labelBox: Rect)
    data class CubicSegment(val start: Offset, val c1: Offset, val c2: Offset, val end: Offset)
}

fun graphGeometry(layout: InteractiveUiGraphLayout): InteractiveUiGraphGeometry {
    val horizontal = !layout.topToBottom
    val count = maxOf(1, layout.layers.indices.maxOfOrNull { layer -> layout.nodes.count { it.layer == layer } } ?: 0)
    val width = 96f + if (horizontal) layout.layers.size * 240f - 72f else count * 184f - 16f
    val height = 96f + if (horizontal) count * 72f - 16f else layout.layers.size * 128f - 72f
    val boxes = layout.nodes.associate { node ->
        val x = 48f + if (horizontal) node.layer * 240f else node.order * 184f
        val y = 48f + if (horizontal) node.order * 72f else node.layer * 128f
        node.id to Rect(x, y, x + 168f, y + 56f)
    }
    val byId = layout.nodes.associateBy { it.id }
    fun route(edge: InteractiveUiGraphInput.Edge, index: Int, slot: Int?): InteractiveUiGraphGeometry.EdgePath {
        val from = boxes.getValue(edge.from)
        val to = boxes.getValue(edge.to)
        val adjacent = byId.getValue(edge.to).layer == byId.getValue(edge.from).layer + 1
        if (adjacent && slot == null) {
            val start = if (horizontal) Offset(from.right, from.center.y) else Offset(from.center.x, from.bottom)
            val end = if (horizontal) Offset(to.left, to.center.y) else Offset(to.center.x, to.top)
            val mid = (start + end) / 2f
            val c1 = if (horizontal) Offset(mid.x, start.y) else Offset(start.x, mid.y)
            val c2 = if (horizontal) Offset(mid.x, end.y) else Offset(end.x, mid.y)
            val label = if (horizontal) {
                Rect(start.x + 8, mid.y - 32, start.x + 64, mid.y)
            } else {
                Rect(mid.x - 84, mid.y - 16, mid.x + 84, mid.y + 16)
            }
            return InteractiveUiGraphGeometry.EdgePath(listOf(InteractiveUiGraphGeometry.CubicSegment(start, c1, c2, end)), label)
        }
        val lane = 12f + index % 5 * 6f
        val points: List<Offset>
        val label: Rect
        if (horizontal) {
            val start = Offset(from.right, from.center.y)
            val end = Offset(to.right, to.center.y)
            val exit = start.x + 18
            val entry = end.x + 36
            val y = if (slot == null) height - lane else height + slot * 48
            points = listOf(start, Offset(exit, start.y), Offset(exit, y), Offset(entry, y), Offset(entry, end.y), end)
            val x = ((exit + entry) / 2 - 84).coerceIn(8f, maxOf(8f, width - 176))
            val labelY = if (slot == null) height else y + 8
            label = Rect(x, labelY, x + 168, labelY + 32)
        } else {
            val start = Offset(from.center.x, from.bottom)
            val end = Offset(to.center.x, to.bottom)
            val exit = start.y + 18
            val entry = end.y + 36
            val x = if (slot == null) width - lane else width + slot * 184
            points = listOf(start, Offset(start.x, exit), Offset(x, exit), Offset(x, entry), Offset(end.x, entry), end)
            val labelX = if (slot == null) width else x + 8
            val y = (exit + entry) / 2 - 16
            label = Rect(labelX, y, labelX + 168, y + 32)
        }
        val segments = points.zipWithNext().mapIndexed { segmentIndex, (start, end) ->
            InteractiveUiGraphGeometry.CubicSegment(start, if (segmentIndex == 0) end else start, end, end)
        }
        return InteractiveUiGraphGeometry.EdgePath(segments, label)
    }
    var outsideSlot = 0
    val allocated = mutableListOf<Rect>()
    val paths = layout.edges.mapIndexed { index, edge ->
        val adjacent = byId.getValue(edge.to).layer == byId.getValue(edge.from).layer + 1
        var path = route(edge, index, if (!edge.label.isNullOrEmpty() && !adjacent) outsideSlot++ else null)
        if (!edge.label.isNullOrEmpty()) {
            if (allocated.any { it.overlaps(path.labelBox) } || boxes.values.any { it.overlaps(path.labelBox) }) {
                path = route(edge, index, outsideSlot++)
            }
            allocated.add(path.labelBox)
        }
        path
    }
    val headings = layout.layers.mapIndexed { index, label ->
        Offset(if (horizontal) 48f + index * 240 else 48f, if (horizontal) 24f else 32f + index * 128) to label
    }
    return InteractiveUiGraphGeometry(
        maxOf(width, 264f, allocated.maxOfOrNull { it.right + 8 } ?: 0f),
        maxOf(height, 152f, allocated.maxOfOrNull { it.bottom + 8 } ?: 0f),
        boxes,
        headings,
        paths,
    )
}
