/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.core.protocol

import kotlinx.serialization.json.JsonObject

/** Ported from element-web apps/web/src/interactive-ui/protocol.ts at bcb08017 (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial). */
enum class InteractiveUiStatus { Active, Submitted, Expired, Superseded }
enum class InteractiveUiTone { Neutral, Info, Success, Warning, Critical }

data class InteractiveUiPayload(
    val uiId: String,
    val revision: Long,
    val status: InteractiveUiStatus,
    val statusText: String?,
    val title: String?,
    /** Wire order; values already trimmed and validated. */
    val state: Map<String, InteractiveUiValue>,
    val root: String,
    /** Only nodes reachable from [root], in wire order. */
    val nodes: List<InteractiveUiNode>,
) {
    val nodesById: Map<String, InteractiveUiNode> = nodes.associateBy { it.id }
}

data class InteractiveUiCondition(val stateKey: String, val comparison: Comparison, val negate: Boolean) {
    sealed interface Comparison {
        data class EqualTo(val value: InteractiveUiValue) : Comparison
        data class OneOf(val values: List<InteractiveUiValue>) : Comparison
    }
}

sealed interface InteractiveUiAction {
    data class SetState(val key: String, val value: InteractiveUiValue) : InteractiveUiAction
    data class ToggleState(val key: String) : InteractiveUiAction

    /** [singleUse] already defaulted to false (D5). */
    data class Submit(val actionId: String, val include: List<String>, val singleUse: Boolean) : InteractiveUiAction
    data class OpenUrl(val url: String) : InteractiveUiAction
}

data class InteractiveUiOption(val value: String, val label: String)
data class InteractiveUiImage(
    val alt: String,
    val mxcUrl: String?,
    /** The validated Matrix EncryptedFile object, verbatim. Exactly one of [mxcUrl]/[encryptedFile] is set. */
    val encryptedFile: JsonObject?,
    val mimetype: String,
    val width: Int?,
    val height: Int?,
)

sealed interface InteractiveUiNode {
    val id: String
    val visibleWhen: InteractiveUiCondition?
    val fallback: String?

    enum class Direction { Vertical, Horizontal }
    enum class Gap { None, Sm, Md, Lg }
    data class Stack(
        override val id: String, override val visibleWhen: InteractiveUiCondition?, override val fallback: String?,
        val children: List<String>, val direction: Direction, val gap: Gap,
    ) : InteractiveUiNode
    data class Card(
        override val id: String, override val visibleWhen: InteractiveUiCondition?, override val fallback: String?,
        val title: String?, val children: List<String>,
    ) : InteractiveUiNode
    data class Tabs(
        override val id: String, override val visibleWhen: InteractiveUiCondition?, override val fallback: String?,
        val bind: String, val tabs: List<Tab>,
    ) : InteractiveUiNode {
        data class Tab(val value: String, val label: String, val child: String)
    }
    data class Collapsible(
        override val id: String, override val visibleWhen: InteractiveUiCondition?, override val fallback: String?,
        val label: String, val children: List<String>, val bind: String?,
    ) : InteractiveUiNode
    data class Divider(
        override val id: String, override val visibleWhen: InteractiveUiCondition?, override val fallback: String?,
    ) : InteractiveUiNode
    data class Heading(
        override val id: String, override val visibleWhen: InteractiveUiCondition?, override val fallback: String?,
        val text: String, val level: Int,
    ) : InteractiveUiNode
    enum class TextTone { Default, Muted, Emphasis }
    enum class TextSize { Body, Caption }
    data class Text(
        override val id: String, override val visibleWhen: InteractiveUiCondition?, override val fallback: String?,
        val text: String, val tone: TextTone, val size: TextSize,
    ) : InteractiveUiNode
    data class RichText(
        override val id: String, override val visibleWhen: InteractiveUiCondition?, override val fallback: String?,
        val text: String, val html: String?,
    ) : InteractiveUiNode
    data class Badge(
        override val id: String, override val visibleWhen: InteractiveUiCondition?, override val fallback: String?,
        val text: String, val tone: InteractiveUiTone,
    ) : InteractiveUiNode
    data class KeyValue(
        override val id: String, override val visibleWhen: InteractiveUiCondition?, override val fallback: String?,
        val items: List<Pair<String, String>>,
    ) : InteractiveUiNode
    data class ItemList(
        override val id: String, override val visibleWhen: InteractiveUiCondition?, override val fallback: String?,
        val items: List<Item>, val ordered: Boolean,
    ) : InteractiveUiNode {
        data class Item(val text: String, val secondary: String?)
    }
    data class Image(
        override val id: String, override val visibleWhen: InteractiveUiCondition?, override val fallback: String?,
        val image: InteractiveUiImage,
    ) : InteractiveUiNode
    data class Carousel(
        override val id: String, override val visibleWhen: InteractiveUiCondition?, override val fallback: String?,
        val label: String, val items: List<Item>,
    ) : InteractiveUiNode {
        data class Item(val id: String, val image: InteractiveUiImage, val title: String, val subtitle: String?,
            val meta: List<String>, val badge: Pair<String, InteractiveUiTone>?, val action: InteractiveUiAction?)
    }
    enum class LinkKind { Source, External }
    data class Link(
        override val id: String, override val visibleWhen: InteractiveUiCondition?, override val fallback: String?,
        val label: String, val url: String, val kind: LinkKind,
    ) : InteractiveUiNode
    data class Table(
        override val id: String, override val visibleWhen: InteractiveUiCondition?, override val fallback: String?,
        val caption: String?, val columns: List<Column>, val rows: List<Map<String, InteractiveUiValue>>, val sortable: Boolean,
    ) : InteractiveUiNode {
        data class Column(val key: String, val label: String, val numeric: Boolean, val alignEnd: Boolean)
    }
    sealed interface ChartData {
        enum class Kind { Bar, Line, Pie }
        data class Categorical(val kind: Kind, val categories: List<String>, val series: List<Series>) : ChartData
        data class Scatter(val series: List<ScatterSeries>) : ChartData
        data class Series(val name: String, val values: List<Double>)
        data class ScatterSeries(val name: String, val points: List<Point>)
        data class Point(val x: Double, val y: Double, val label: String?)
    }
    data class Chart(
        override val id: String, override val visibleWhen: InteractiveUiCondition?, override val fallback: String?,
        val title: String?, val xLabel: String?, val yLabel: String?, val unit: String?, val data: ChartData,
    ) : InteractiveUiNode
    data class Graph(
        override val id: String, override val visibleWhen: InteractiveUiCondition?, override val fallback: String?,
        val topToBottom: Boolean, val layers: List<String>, val nodes: List<GraphNode>, val edges: List<GraphEdge>, val bindSelected: String?,
    ) : InteractiveUiNode {
        data class GraphNode(val id: String, val label: String, val detail: String?, val layer: Int, val tone: InteractiveUiTone)
        data class GraphEdge(val from: String, val to: String, val label: String?, val dashed: Boolean)
    }
    enum class ButtonVariant { Primary, Secondary, Danger }
    data class Button(
        override val id: String, override val visibleWhen: InteractiveUiCondition?, override val fallback: String?,
        val label: String, val variant: ButtonVariant, val action: InteractiveUiAction,
    ) : InteractiveUiNode
    data class Select(
        override val id: String, override val visibleWhen: InteractiveUiCondition?, override val fallback: String?,
        val label: String, val bind: String, val options: List<InteractiveUiOption>,
    ) : InteractiveUiNode
    data class Choice(
        override val id: String, override val visibleWhen: InteractiveUiCondition?, override val fallback: String?,
        val label: String, val bind: String, val options: List<InteractiveUiOption>, val multiple: Boolean,
    ) : InteractiveUiNode
    data class Toggle(
        override val id: String, override val visibleWhen: InteractiveUiCondition?, override val fallback: String?,
        val label: String, val bind: String,
    ) : InteractiveUiNode
    data class TextInput(
        override val id: String, override val visibleWhen: InteractiveUiCondition?, override val fallback: String?,
        val label: String, val bind: String, val multiline: Boolean, val placeholder: String?, val maxLength: Int, val required: Boolean,
    ) : InteractiveUiNode
    data class NumberInput(
        override val id: String, override val visibleWhen: InteractiveUiCondition?, override val fallback: String?,
        val label: String, val bind: String, val min: Double?, val max: Double?, val step: Double?, val slider: Boolean,
    ) : InteractiveUiNode

    /** [singleUse] already defaulted to true (D5). */
    data class Form(
        override val id: String, override val visibleWhen: InteractiveUiCondition?, override val fallback: String?,
        val children: List<String>, val submitLabel: String, val actionId: String, val singleUse: Boolean,
    ) : InteractiveUiNode
    data class Unknown(
        override val id: String, override val visibleWhen: InteractiveUiCondition?, override val fallback: String?,
    ) : InteractiveUiNode
}
