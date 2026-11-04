/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.ui.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.swipe.SwipeSensitivity
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.utils.BlockAppDrawerGestures
import io.element.android.libraries.interactiveui.ui.R
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiBadgeTone
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Ported from element-web packages/shared-components/src/room/timeline/event-tile/body/InteractiveUiView/graph/InteractiveUiGraphView.tsx
 * at bcb08017 (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial).
 */
@Composable
fun InteractiveUiGraphView(
    layout: InteractiveUiGraphLayout,
    selectedId: String?,
    showAsList: Boolean,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
    onToggleList: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val geometry = remember(layout) { graphGeometry(layout) }
    val scroll = rememberScrollState()
    if (scroll.isScrollInProgress) BlockAppDrawerGestures()
    val density = LocalDensity.current
    val fontScale = density.fontScale.coerceAtLeast(1f)
    val measurer = rememberTextMeasurer()
    val nodeStyle = ElementTheme.typography.fontBodyLgRegular
    val labelStyle = ElementTheme.typography.fontBodySmRegular
    fun textHeight(text: String, width: Float, node: Boolean): Float {
        val pixels = measurer.measure(
            text,
            if (node) nodeStyle else labelStyle,
            constraints = Constraints(maxWidth = with(density) { (width * fontScale).dp.roundToPx() }),
        ).size.height
        return with(density) { pixels.toDp().value }
    }
    // Enlarge the diagram uniformly when wrapping needs more room, preserving route geometry.
    val nodeScale = layout.nodes.maxOfOrNull { (textHeight(it.label, 152f, true) + 16f) / 56f } ?: 1f
    val labelScale = geometry.paths.mapIndexedNotNull { index, path ->
        layout.edges[index].label?.let { textHeight(it, path.labelBox.width, false) / 32f }
    }.maxOrNull() ?: 1f
    val headingScale = layout.layers.maxOfOrNull { textHeight(it, 168f, false) / 24f } ?: 1f
    val scale = maxOf(fontScale, nodeScale, labelScale, headingScale)
    val byId = remember(layout) { layout.nodes.associateBy { it.id } }
    val edgeColor = ElementTheme.colors.textSecondary
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.screen_interactive_ui_graph), Modifier.semantics { heading() })
        Text(
            stringResource(R.string.screen_interactive_ui_relationships_list),
            Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { selected = showAsList }
                .clickable(enabled = enabled, role = Role.Button) { onToggleList(!showAsList) }
                .padding(8.dp),
        )
        if (showAsList) {
            layout.nodes.forEach { node ->
                GraphNodeButton(node, node.id == selectedId, enabled, { onSelect(if (node.id == selectedId) null else node.id) })
                node.detail?.let { Text(it) }
            }
            layout.edges.forEach { edge ->
                Text(stringResource(
                    R.string.screen_interactive_ui_relationship,
                    byId.getValue(edge.from).label,
                    byId.getValue(edge.to).label,
                    edge.label.orEmpty()
                ))
            }
        } else {
            SwipeSensitivity(1f / 3f) {
                Box(Modifier.horizontalScroll(scroll)) {
                    // Canvas and overlays share physical coordinates; text still inherits the surrounding layout direction.
                    Box(
                        Modifier.size((geometry.width * scale).dp, (geometry.height * scale).dp).testTag("interactive-ui-graph-diagram"),
                        contentAlignment = AbsoluteAlignment.TopLeft,
                    ) {
                        Canvas(Modifier.matchParentSize()) {
                            fun Offset.pixels() = this * (density.density * scale)
                            geometry.paths.forEachIndexed { index, edge ->
                                val path = Path()
                                edge.segments.firstOrNull()?.let { path.moveTo(it.start.pixels().x, it.start.pixels().y) }
                                edge.segments.forEach { segment ->
                                    val c1 = segment.c1.pixels()
                                    val c2 = segment.c2.pixels()
                                    val end = segment.end.pixels()
                                    path.cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
                                }
                                drawPath(path, edgeColor, style = Stroke(2.dp.toPx(), pathEffect =
                                    if (layout.edges[index].dashed) PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 4.dp.toPx())) else null))
                                edge.segments.lastOrNull()?.let { last ->
                                    val end = last.end.pixels()
                                    val prior = (if (last.c2 == last.end) last.start else last.c2).pixels()
                                    val angle = atan2(end.y - prior.y, end.x - prior.x)
                                    for (delta in listOf(-0.5f, 0.5f)) {
                                        val tip = end - Offset(cos(angle + delta), sin(angle + delta)) * 8.dp.toPx()
                                        drawLine(edgeColor, end, tip, 2.dp.toPx())
                                    }
                                }
                            }
                        }
                        geometry.headings.forEach { (position, label) ->
                            Text(
                                label,
                                Modifier.absoluteOffset((position.x * scale).dp, ((position.y - 20) * scale).dp)
                                .width((168 * scale).dp)
                                .semantics { heading() },
                                style = ElementTheme.typography.fontBodySmMedium
                            )
                        }
                        geometry.paths.forEachIndexed { index, path ->
                            layout.edges[index].label?.let { label ->
                                Text(
                                    label,
                                    Modifier.absoluteOffset((path.labelBox.left * scale).dp, (path.labelBox.top * scale).dp)
                                    .width((path.labelBox.width * scale).dp),
                                    style = ElementTheme.typography.fontBodySmRegular
                                )
                            }
                        }
                        layout.nodes.forEach { node ->
                            val box = geometry.boxes.getValue(node.id)
                            GraphNodeButton(
                                node,
                                node.id == selectedId,
                                enabled,
                                { onSelect(if (node.id == selectedId) null else node.id) },
                                Modifier.absoluteOffset((box.left * scale).dp, (box.top * scale).dp)
                                    .size((box.width * scale).dp, (box.height * scale).dp)
                            )
                        }
                    }
                }
            }
        }
        byId[selectedId]?.let { node ->
            Text(node.label, Modifier.semantics { heading() })
            node.detail?.let { Text(it) }
        }
    }
}

@Composable
private fun GraphNodeButton(
    node: InteractiveUiGraphLayout.Node,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ElementTheme.colors
    val color = when (node.tone) {
        InteractiveUiBadgeTone.Neutral -> colors.textPrimary
        InteractiveUiBadgeTone.Info -> colors.textInfoPrimary
        InteractiveUiBadgeTone.Success -> colors.textSuccessPrimary
        InteractiveUiBadgeTone.Warning -> colors.textWarningPrimary
        InteractiveUiBadgeTone.Critical -> colors.textCriticalPrimary
    }
    val description = stringResource(R.string.a11y_interactive_ui_graph_node, node.label, node.detail.orEmpty())
    Text(
        node.label,
        modifier.heightIn(min = 56.dp).border(if (isSelected) 2.dp else 1.dp, color, RoundedCornerShape(8.dp))
            .semantics {
                selected = isSelected
                contentDescription = description
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(8.dp),
        color = color,
        style = ElementTheme.typography.fontBodyLgRegular,
    )
}
