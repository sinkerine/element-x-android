/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.ui

import androidx.compose.ui.unit.dp
import io.element.android.libraries.htmlrenderer.api.DocumentNode
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiAction
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiTone
import io.element.android.libraries.interactiveui.core.session.InteractiveUiPhase
import io.element.android.libraries.interactiveui.core.session.InteractiveUiSessionState
import io.element.android.libraries.interactiveui.core.state.InteractiveUiStateRules
import io.element.android.libraries.interactiveui.ui.graph.InteractiveUiGraphInput
import io.element.android.libraries.interactiveui.ui.graph.layoutLayeredGraph
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiBadgeTone
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiViewEvent
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiViewState
import io.element.android.libraries.interactiveui.ui.table.InteractiveUiCell
import io.element.android.libraries.interactiveui.ui.table.InteractiveUiTableColumn
import io.element.android.libraries.matrix.api.media.MediaSource
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableList
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiNode as Node
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiValue as Value
import io.element.android.libraries.interactiveui.ui.chart.InteractiveUiChartModel as Chart
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiRenderNode as Render

/** Ported from element-web apps/web/src/viewmodels/message-body/InteractiveUiBodyViewModel.ts at bcb08017
 * (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial).
 */
object InteractiveUiRenderTreeBuilder {
    fun build(
        session: InteractiveUiSessionState,
        richText: ImmutableMap<String, DocumentNode>,
        media: ImmutableMap<String, MediaSource>,
        hideMedia: Boolean,
        senderDisplayName: String,
        eventSink: (InteractiveUiViewEvent) -> Unit,
    ): InteractiveUiViewState {
        val enabled = session.phase == InteractiveUiPhase.Active
        fun text(key: String) = (session.values[key] as? Value.Text)?.value.orEmpty()
        fun checked(key: String) = (session.values[key] as? Value.Bool)?.value == true
        fun render(id: String): Render? {
            val node = session.payload.nodesById[id] ?: return null
            if (node.visibleWhen?.let { !InteractiveUiStateRules.evaluateCondition(it, session.values) } == true) return null
            fun children(ids: List<String>) = ids.mapNotNull(::render).toImmutableList()
            val pending = session.pendingNodeId == id && session.phase == InteractiveUiPhase.Sending
            return when (node) {
                is Node.Stack -> Render.Stack(
                    id,
                    node.direction == Node.Direction.Horizontal,
                    when (node.gap) {
                    Node.Gap.None -> 0.dp
                    Node.Gap.Sm -> 4.dp
                    Node.Gap.Md -> 8.dp
                    Node.Gap.Lg -> 16.dp
                },
                    children(node.children)
                )
                is Node.Card -> Render.Card(id, node.title, children(node.children))
                is Node.Tabs -> Render.Tabs(id, text(node.bind), node.tabs.mapNotNull { tab ->
                    render(tab.child)?.let { Render.Tabs.Tab(tab.value, tab.label, it) }
                }.toImmutableList(), enabled)
                is Node.Collapsible -> Render.Collapsible(
                    id,
                    node.label,
                    node.bind?.let(::checked) ?: (session.collapsibleOpen[id] == true),
                    enabled,
                    children(node.children)
                )
                is Node.Divider -> Render.Divider(id)
                is Node.Heading -> Render.Heading(id, node.text, node.level)
                is Node.Text -> Render.Text(id, node.text, node.tone, node.size == Node.TextSize.Caption)
                is Node.RichText -> Render.RichText(id, node.text, richText[id])
                is Node.Badge -> Render.Badge(id, node.text, node.tone.badgeTone())
                is Node.KeyValue -> Render.KeyValue(id, node.items.toImmutableList())
                is Node.ItemList -> Render.ItemList(id, node.ordered, node.items.map { it.text to it.secondary }.toImmutableList())
                is Node.Image -> Render.Image(
                    id,
                    node.image.alt,
                    media[id],
                    node.image.mimetype,
                    node.image.width?.let { width -> node.image.height?.takeIf { it > 0 }?.let { width.toFloat() / it } },
                    hideMedia
                )
                is Node.Button -> Render.Button(id, node.label, node.variant, enabled, pending, node.action is InteractiveUiAction.OpenUrl)
                is Node.Select -> Render.Select(
                    id,
                    node.label,
                    (session.values[node.bind] as? Value.Text)?.value,
                    node.options.toImmutableList(),
                    enabled
                )
                is Node.Choice -> Render.Choice(id, node.label, when (val value = session.values[node.bind]) {
                    is Value.Text -> listOf(value.value)
                    is Value.TextList -> value.values
                    else -> emptyList()
                }.toImmutableList(), node.multiple, node.options.toImmutableList(), enabled)
                is Node.Toggle -> Render.Toggle(id, node.label, checked(node.bind), enabled)
                is Node.TextInput -> Render.TextInput(
                    id,
                    node.label,
                    text(node.bind),
                    node.multiline,
                    node.placeholder,
                    node.maxLength,
                    node.required,
                    node.bind in session.invalidKeys,
                    enabled
                )
                is Node.NumberInput -> Render.NumberInput(
                    id,
                    node.label,
                    if (node.bind in session.numberDrafts) session.numberDrafts[node.bind] else (session.values[node.bind] as? Value.Number)?.value,
                    node.min,
                    node.max,
                    node.step,
                    node.slider,
                    node.bind in session.invalidKeys || node.bind in session.numberDrafts,
                    enabled
                )
                is Node.Form -> Render.Form(id, node.submitLabel, children(node.children), enabled, pending)
                is Node.Graph -> Render.Graph(
                    id,
                    layoutLayeredGraph(InteractiveUiGraphInput(
                        node.topToBottom,
                        node.layers,
                    node.nodes.map { InteractiveUiGraphInput.Node(it.id, it.label, it.detail, it.layer, it.tone.badgeTone()) },
                    node.edges.map { InteractiveUiGraphInput.Edge(it.from, it.to, it.label, it.dashed) }
                    )),
                    node.bindSelected?.let { (session.values[it] as? Value.Text)?.value } ?: session.graphSelection[id],
                    id in session.graphListMode,
                    enabled
                )
                is Node.Chart -> Render.Chart(id, node.chart(), id in session.chartDataMode, enabled)
                is Node.Table -> Render.Table(
                    id,
                    node.caption,
                    node.columns.map { InteractiveUiTableColumn(it.key, it.label, it.numeric, it.alignEnd) }.toImmutableList(),
                    node.rows.map { row ->
                        node.columns.map { column ->
                            when (val value = row[column.key]) {
                        is Value.Text -> InteractiveUiCell.Text(value.value)
                        is Value.Number -> InteractiveUiCell.Number(value.value)
                        else -> InteractiveUiCell.Empty
                    }
                        }.toImmutableList()
                    }.toImmutableList(),
                    session.tableSort[id],
                    node.sortable,
                    enabled
                )
                is Node.Carousel -> Render.Carousel(
                    id,
                    node.label,
                    (session.carouselIndex[id] ?: 0).coerceIn(0, (node.items.size - 1).coerceAtLeast(0)),
                    node.items.map { item ->
                        Render.Carousel.Item(
                            item.id,
                            media["$id/${item.id}"],
                            item.image.alt,
                            item.title,
                            item.subtitle,
                            item.meta.toImmutableList(),
                            item.badge?.let { it.first to it.second.badgeTone() },
                            item.title.takeIf { item.action != null },
                            item.action is InteractiveUiAction.OpenUrl,
                            hideMedia
                        )
                    }.toImmutableList(),
                    enabled
                )
                is Node.Link -> Render.Link(
                    id,
                    node.label,
                    node.url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#'),
                    node.kind == Node.LinkKind.Source,
                    enabled
                )
                is Node.Unknown -> Render.Unknown(id, node.fallback)
            }
        }
        return InteractiveUiViewState(
            session.payload.title,
            session.phase,
            session.notice,
            senderDisplayName,
            session.confirmationOverdue,
            session.showAsText,
            session.urlRequest,
            render(session.payload.root) ?: Render.Stack(session.payload.root, false, 0.dp, emptyList<Render>().toImmutableList()),
            eventSink
        )
    }
}

private fun InteractiveUiTone.badgeTone() = InteractiveUiBadgeTone.valueOf(name)

private fun Node.Chart.chart(): Chart = when (val data = data) {
    is Node.ChartData.Categorical -> when (data.kind) {
        Node.ChartData.Kind.Pie -> Chart.Pie(
            title,
            unit,
            data.categories.toImmutableList(),
            data.series.firstOrNull()?.values.orEmpty().toImmutableList()
        )
        else -> Chart.Categorical(
            title,
            unit,
            data.kind == Node.ChartData.Kind.Line,
            xLabel,
            yLabel,
            data.categories.toImmutableList(),
            data.series.map { Chart.Series(it.name, it.values.toImmutableList()) }.toImmutableList()
        )
    }
    is Node.ChartData.Scatter -> Chart.Scatter(title, unit, xLabel, yLabel, data.series.map { series ->
        Chart.ScatterSeries(series.name, series.points.map { Triple(it.x, it.y, it.label) }.toImmutableList())
    }.toImmutableList())
}

/** Applies timeline protection recursively, including inactive tab panels and collapsed children. */
fun InteractiveUiViewState.withHiddenMedia(hide: Boolean): InteractiveUiViewState {
    fun Render.hidden(): Render = when (this) {
        is Render.Image -> copy(hidden = hide)
        is Render.Carousel -> copy(items = items.map { it.copy(hidden = hide) }.toImmutableList())
        is Render.Stack -> copy(children = children.map { it.hidden() }.toImmutableList())
        is Render.Card -> copy(children = children.map { it.hidden() }.toImmutableList())
        is Render.Tabs -> copy(tabs = tabs.map { it.copy(content = it.content.hidden()) }.toImmutableList())
        is Render.Collapsible -> copy(children = children.map { it.hidden() }.toImmutableList())
        is Render.Form -> copy(children = children.map { it.hidden() }.toImmutableList())
        else -> this
    }
    return copy(root = root.hidden())
}
