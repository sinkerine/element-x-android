/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.HorizontalDivider
import io.element.android.libraries.designsystem.theme.components.Surface
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.htmlrenderer.api.ui.HtmlMessageContent
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiNode
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiValue
import io.element.android.libraries.interactiveui.ui.R
import io.element.android.libraries.interactiveui.ui.chart.InteractiveUiChartView
import io.element.android.libraries.interactiveui.ui.graph.InteractiveUiGraphView
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiRenderNode
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiViewEvent
import io.element.android.libraries.interactiveui.ui.table.InteractiveUiTableView
import kotlinx.collections.immutable.ImmutableList

/** Ported from element-web packages/shared-components/src/room/timeline/event-tile/body/InteractiveUiView/InteractiveUiView.tsx
 * at bcb08017 (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial).
 */
@Composable
internal fun InteractiveUiNodeView(
    node: InteractiveUiRenderNode,
    active: Boolean,
    sink: (InteractiveUiViewEvent) -> Unit,
    onShowMedia: () -> Unit,
) {
    when (node) {
        is InteractiveUiRenderNode.Stack -> {
            if (node.horizontal) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(node.gap), verticalArrangement = Arrangement.spacedBy(node.gap)) {
                    node.children.forEach { child ->
                        key(child.id) {
                            Box(Modifier.widthIn(min = 200.dp).weight(1f)) { InteractiveUiNodeView(child, active, sink, onShowMedia) }
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(node.gap)) { Children(node.children, active, sink, onShowMedia) }
            }
        }
        is InteractiveUiRenderNode.Card -> Surface(color = ElementTheme.colors.bgSubtleSecondary, shape = RoundedCornerShape(8.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                node.title?.let { Text(it, Modifier.semantics { heading() }, style = ElementTheme.typography.fontHeadingSmMedium) }
                Children(node.children, active, sink, onShowMedia)
            }
        }
        is InteractiveUiRenderNode.Tabs -> Column {
            FlowRow(Modifier.selectableGroup()) {
                node.tabs.forEach { tab ->
                    Text(
                        tab.label,
                        Modifier.heightIn(min = 48.dp).selectable(tab.value == node.selected, active && node.enabled, Role.Tab) {
                            sink(InteractiveUiViewEvent.SetValue(node.id, InteractiveUiValue.Text(tab.value)))
                        }.padding(12.dp),
                        color = if (tab.value == node.selected) ElementTheme.colors.textActionPrimary else ElementTheme.colors.textSecondary,
                    )
                }
            }
            node.tabs.firstOrNull { it.value == node.selected }?.let { InteractiveUiNodeView(it.content, active && node.enabled, sink, onShowMedia) }
        }
        is InteractiveUiRenderNode.Collapsible -> Column {
            val description = stringResource(if (node.open) R.string.a11y_interactive_ui_expanded else R.string.a11y_interactive_ui_collapsed)
            ActionButton(
                node.label + if (node.open) " −" else " +",
                enabled = active && node.enabled,
                pending = false,
                variant = InteractiveUiNode.ButtonVariant.Secondary,
                external = false,
                modifier = Modifier.semantics { stateDescription = description },
                onClick = { sink(InteractiveUiViewEvent.SetValue(node.id, InteractiveUiValue.Bool(!node.open))) },
            )
            if (node.open) Children(node.children, active && node.enabled, sink, onShowMedia)
        }
        is InteractiveUiRenderNode.Divider -> HorizontalDivider()
        is InteractiveUiRenderNode.Heading -> Text(
            node.text,
            Modifier.semantics { heading() },
            style = when (node.level) {
                1 -> ElementTheme.typography.fontHeadingMdBold
                2 -> ElementTheme.typography.fontHeadingSmMedium
                else -> ElementTheme.typography.fontBodyLgRegular
            },
        )
        is InteractiveUiRenderNode.Text -> Text(
            node.text,
            color = if (node.tone == InteractiveUiNode.TextTone.Muted) ElementTheme.colors.textSecondary else ElementTheme.colors.textPrimary,
            style = (if (node.caption) ElementTheme.typography.fontBodySmRegular else ElementTheme.typography.fontBodyLgRegular)
                .copy(fontWeight = if (node.tone == InteractiveUiNode.TextTone.Emphasis) FontWeight.Medium else FontWeight.Normal),
        )
        is InteractiveUiRenderNode.RichText -> if (node.document != null) HtmlMessageContent(node.document) else Text(node.text)
        is InteractiveUiRenderNode.Badge -> InteractiveUiBadge(node.text, node.tone)
        is InteractiveUiRenderNode.KeyValue -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            node.items.forEach { (label, value) ->
                Column {
                Text(label, color = ElementTheme.colors.textSecondary)
                Text(value)
            }
            }
        }
        is InteractiveUiRenderNode.ItemList -> Column {
            node.items.forEachIndexed { index, (text, secondary) ->
                Column {
                    Text((if (node.ordered) "${index + 1}. " else "• ") + text)
                    secondary?.let { Text(it, color = ElementTheme.colors.textSecondary) }
                }
            }
        }
        is InteractiveUiRenderNode.Image -> InteractiveUiImage(node.alt, node.source, node.aspectRatio, node.hidden, onShowMedia)
        is InteractiveUiRenderNode.Carousel -> InteractiveUiCarousel(node, active && node.enabled, sink, onShowMedia)
        is InteractiveUiRenderNode.Table -> InteractiveUiTableView(
            node.caption,
            node.columns,
            node.rows,
            node.sort?.let { it.columnKey to it.descending },
            node.sortable,
            active && node.enabled,
            { sink(InteractiveUiViewEvent.SortTable(node.id, it)) },
        )
        is InteractiveUiRenderNode.Chart -> InteractiveUiChartView(
            node.chart,
            node.showData,
            active && node.enabled,
            { sink(InteractiveUiViewEvent.SetChartDataMode(node.id, it)) }
        )
        is InteractiveUiRenderNode.Graph -> InteractiveUiGraphView(
            node.layout,
            node.selectedId,
            node.showAsList,
            active && node.enabled,
            { sink(InteractiveUiViewEvent.SelectGraphNode(node.id, it)) },
            { sink(InteractiveUiViewEvent.SetGraphListMode(node.id, it)) }
        )
        is InteractiveUiRenderNode.Button -> ActionButton(
            node.label,
            active && node.enabled,
            node.pending,
            node.variant,
            node.external,
            onClick = { sink(InteractiveUiViewEvent.Activate(node.id)) }
        )
        is InteractiveUiRenderNode.Select -> Select(node, active && node.enabled, sink)
        is InteractiveUiRenderNode.Choice -> Choice(node, active && node.enabled, sink)
        is InteractiveUiRenderNode.Toggle -> InteractiveUiSwitch(node.label, node.checked, active && node.enabled) {
            sink(InteractiveUiViewEvent.SetValue(node.id, InteractiveUiValue.Bool(it)))
        }
        is InteractiveUiRenderNode.TextInput -> TextInput(node, active && node.enabled, sink)
        is InteractiveUiRenderNode.NumberInput -> NumberInput(node, active && node.enabled, sink)
        is InteractiveUiRenderNode.Form -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Children(node.children, active && node.enabled && !node.pending, sink, onShowMedia)
            ActionButton(
                node.submitLabel,
                active && node.enabled,
                node.pending,
                InteractiveUiNode.ButtonVariant.Primary,
                false,
                onClick = { sink(InteractiveUiViewEvent.SubmitForm(node.id)) }
            )
        }
        is InteractiveUiRenderNode.Link -> ActionButton(
            node.label + " · " + node.host + " (" + stringResource(
                if (node.source) R.string.screen_interactive_ui_source else R.string.screen_interactive_ui_external_link
            ) + ")",
            enabled = active && node.enabled,
            pending = false,
            variant = InteractiveUiNode.ButtonVariant.Secondary,
            external = false,
            onClick = { sink(InteractiveUiViewEvent.OpenLink(node.id)) },
        )
        is InteractiveUiRenderNode.Unknown -> Text(
            node.fallback ?: stringResource(R.string.screen_interactive_ui_unknown_content),
            color = ElementTheme.colors.textSecondary
        )
    }
}

@Composable
private fun Children(nodes: ImmutableList<InteractiveUiRenderNode>, active: Boolean, sink: (InteractiveUiViewEvent) -> Unit, onShowMedia: () -> Unit) {
    nodes.forEach { child -> key(child.id) { InteractiveUiNodeView(child, active, sink, onShowMedia) } }
}
