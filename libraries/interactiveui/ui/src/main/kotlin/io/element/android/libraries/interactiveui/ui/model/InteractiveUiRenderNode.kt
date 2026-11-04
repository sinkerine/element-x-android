/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.ui.model

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import io.element.android.libraries.htmlrenderer.api.DocumentNode
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiNode
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiOption
import io.element.android.libraries.interactiveui.core.session.InteractiveUiTableSort
import io.element.android.libraries.interactiveui.ui.chart.InteractiveUiChartModel
import io.element.android.libraries.interactiveui.ui.graph.InteractiveUiGraphLayout
import io.element.android.libraries.interactiveui.ui.table.InteractiveUiCell
import io.element.android.libraries.interactiveui.ui.table.InteractiveUiTableColumn
import io.element.android.libraries.matrix.api.media.MediaSource
import kotlinx.collections.immutable.ImmutableList

/** Ported from element-web packages/shared-components/src/room/timeline/event-tile/body/InteractiveUiView/types.ts
 * at bcb08017 (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial).
 */
@Immutable
sealed interface InteractiveUiRenderNode {
    val id: String
    data class Stack(override val id: String, val horizontal: Boolean, val gap: Dp,
        val children: ImmutableList<InteractiveUiRenderNode>) : InteractiveUiRenderNode
    data class Card(override val id: String, val title: String?, val children: ImmutableList<InteractiveUiRenderNode>) : InteractiveUiRenderNode
    data class Tabs(override val id: String, val selected: String, val tabs: ImmutableList<Tab>, val enabled: Boolean) : InteractiveUiRenderNode {
        data class Tab(val value: String, val label: String, val content: InteractiveUiRenderNode)
    }
    data class Collapsible(override val id: String, val label: String, val open: Boolean, val enabled: Boolean,
        val children: ImmutableList<InteractiveUiRenderNode>) : InteractiveUiRenderNode
    data class Divider(override val id: String) : InteractiveUiRenderNode
    data class Heading(override val id: String, val text: String, val level: Int) : InteractiveUiRenderNode
    data class Text(override val id: String, val text: String, val tone: InteractiveUiNode.TextTone, val caption: Boolean) : InteractiveUiRenderNode
    data class RichText(override val id: String, val text: String, val document: DocumentNode?) : InteractiveUiRenderNode
    data class Badge(override val id: String, val text: String, val tone: InteractiveUiBadgeTone) : InteractiveUiRenderNode
    data class KeyValue(override val id: String, val items: ImmutableList<Pair<String, String>>) : InteractiveUiRenderNode
    data class ItemList(override val id: String, val ordered: Boolean, val items: ImmutableList<Pair<String, String?>>) : InteractiveUiRenderNode
    data class Image(override val id: String, val alt: String, val source: MediaSource?, val mimeType: String,
        val aspectRatio: Float?, val hidden: Boolean) : InteractiveUiRenderNode
    data class Table(override val id: String, val caption: String?, val columns: ImmutableList<InteractiveUiTableColumn>,
        val rows: ImmutableList<ImmutableList<InteractiveUiCell>>, val sort: InteractiveUiTableSort?, val sortable: Boolean,
        val enabled: Boolean) : InteractiveUiRenderNode
    data class Chart(override val id: String, val chart: InteractiveUiChartModel, val showData: Boolean, val enabled: Boolean) : InteractiveUiRenderNode
    data class Graph(override val id: String, val layout: InteractiveUiGraphLayout, val selectedId: String?,
        val showAsList: Boolean, val enabled: Boolean) : InteractiveUiRenderNode
    data class Button(override val id: String, val label: String, val variant: InteractiveUiNode.ButtonVariant,
        val enabled: Boolean, val pending: Boolean, val external: Boolean) : InteractiveUiRenderNode
    data class Select(override val id: String, val label: String, val selected: String?,
        val options: ImmutableList<InteractiveUiOption>, val enabled: Boolean) : InteractiveUiRenderNode
    data class Choice(override val id: String, val label: String, val selected: ImmutableList<String>, val multiple: Boolean,
        val options: ImmutableList<InteractiveUiOption>, val enabled: Boolean) : InteractiveUiRenderNode
    data class Toggle(override val id: String, val label: String, val checked: Boolean, val enabled: Boolean) : InteractiveUiRenderNode
    data class TextInput(override val id: String, val label: String, val value: String, val multiline: Boolean,
        val placeholder: String?, val maxLength: Int, val required: Boolean, val invalid: Boolean, val enabled: Boolean) : InteractiveUiRenderNode
    data class NumberInput(override val id: String, val label: String, val value: Double?, val min: Double?, val max: Double?,
        val step: Double?, val slider: Boolean, val invalid: Boolean, val enabled: Boolean) : InteractiveUiRenderNode
    data class Form(override val id: String, val submitLabel: String, val children: ImmutableList<InteractiveUiRenderNode>,
        val enabled: Boolean, val pending: Boolean) : InteractiveUiRenderNode
    data class Carousel(override val id: String, val label: String, val index: Int, val items: ImmutableList<Item>,
        val enabled: Boolean) : InteractiveUiRenderNode {
        data class Item(val id: String, val source: MediaSource?, val alt: String, val title: String, val subtitle: String?,
            val meta: ImmutableList<String>, val badge: Pair<String, InteractiveUiBadgeTone>?, val actionLabel: String?,
            val external: Boolean, val hidden: Boolean)
    }
    data class Link(override val id: String, val label: String, val host: String, val source: Boolean, val enabled: Boolean) : InteractiveUiRenderNode
    data class Unknown(override val id: String, val fallback: String?) : InteractiveUiRenderNode
}
