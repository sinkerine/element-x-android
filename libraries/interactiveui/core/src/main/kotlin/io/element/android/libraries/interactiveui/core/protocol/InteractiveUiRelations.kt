/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.core.protocol

import io.element.android.libraries.interactiveui.core.state.InteractiveUiStateKind
import io.element.android.libraries.interactiveui.core.state.InteractiveUiStateRules

/** Ported from element-web apps/web/src/interactive-ui/protocol.ts at bcb08017 (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial). */
internal fun validateRelations(payload: InteractiveUiPayload): Set<String> {
    val byId = linkedMapOf<String, InteractiveUiNode>()
    val indices = mutableMapOf<String, Int>()
    var images = 0
    payload.nodes.forEachIndexed { index, node ->
        val path = "nodes[$index]"
        if (node.id in byId) fail("$path.id", InteractiveUiErrorCode.DuplicateId)
        byId[node.id] = node
        indices[node.id] = index
        if (node is InteractiveUiNode.Image) images++
        if (node is InteractiveUiNode.Carousel) {
            images += node.items.size
            val ids = mutableSetOf<String>()
            node.items.forEachIndexed { j, item -> if (!ids.add(item.id)) fail("$path.items[$j].id", InteractiveUiErrorCode.DuplicateId) }
        }
        if (images > 24) fail(path, InteractiveUiErrorCode.TooMany)
    }
    val visited = linkedSetOf<String>()
    val active = mutableSetOf<String>()
    val parents = mutableSetOf<String>()
    fun walk(id: String, depth: Int, path: String) {
        val node = byId[id] ?: fail(path, InteractiveUiErrorCode.UnknownRef)
        if (id in active) fail(path, InteractiveUiErrorCode.Cycle)
        if (depth > 12) fail(path, InteractiveUiErrorCode.TooDeep)
        if (!visited.add(id)) return
        active.add(id)
        InteractiveUiStateRules.childIds(node).forEachIndexed { j, child ->
            val childPath = "nodes[${indices[id]}]." + if (node is InteractiveUiNode.Tabs) "tabs[$j].child" else "children[$j]"
            if (child in active) fail(childPath, InteractiveUiErrorCode.Cycle)
            if (child in parents || child == payload.root) fail(childPath, InteractiveUiErrorCode.DuplicateId)
            parents.add(child)
            walk(child, depth + 1, childPath)
        }
        active.remove(id)
    }
    walk(payload.root, 1, "root")
    val reachable = visited.toSet()
    val referenced = payload.nodes.flatMap(InteractiveUiStateRules::childIds).toSet()
    payload.nodes.filter { it.id !in visited && it.id !in referenced }.forEach { walk(it.id, 1, "nodes[${indices[it.id]}]") }
    payload.nodes.filter { it.id !in visited }.forEach { walk(it.id, 1, "nodes[${indices[it.id]}]") }
    val retained = payload.copy(nodes = payload.nodes.filter { it.id in reachable })
    fun reference(key: String, path: String, kind: InteractiveUiStateKind? = null) {
        if (key !in payload.state) fail(path, InteractiveUiErrorCode.UnknownRef)
        if (kind != null && InteractiveUiStateRules.stateKind(retained, key) != kind) fail(path, InteractiveUiErrorCode.TypeMismatch)
    }
    fun action(action: InteractiveUiAction, path: String) {
        when (action) {
            is InteractiveUiAction.Submit -> action.include.forEachIndexed { i, key -> reference(key, "$path.include[$i]") }
            is InteractiveUiAction.ToggleState -> reference(action.key, "$path.key", InteractiveUiStateKind.Bool)
            is InteractiveUiAction.SetState -> {
                reference(action.key, "$path.key")
                if (!InteractiveUiStateRules.acceptsStateValue(retained, action.key, action.value)) fail("$path.value", InteractiveUiErrorCode.TypeMismatch)
            }
            else -> Unit
        }
    }
    payload.nodes.forEachIndexed { i, node ->
        val path = "nodes[$i]"
        if (node.id in reachable) {
            node.visibleWhen?.let { condition ->
                reference(condition.stateKey, "$path.visible_when.state")
                val values = when (val comparison = condition.comparison) {
                    is InteractiveUiCondition.Comparison.EqualTo -> listOf(comparison.value)
                    is InteractiveUiCondition.Comparison.OneOf -> comparison.values
                }
                val kind = InteractiveUiStateRules.stateKind(retained, condition.stateKey)
                if (values.any { value ->
                    value != InteractiveUiValue.Null && when (value) {
                    is InteractiveUiValue.Text -> kind != InteractiveUiStateKind.Text
                    is InteractiveUiValue.Number -> kind != InteractiveUiStateKind.Number
                    is InteractiveUiValue.Bool -> kind != InteractiveUiStateKind.Bool
                    else -> true
                }
                }) {
                    fail("$path.visible_when", InteractiveUiErrorCode.TypeMismatch)
                }
            }
            InteractiveUiStateRules.binding(node)?.let { bind ->
                val kind = when (node) {
                    is InteractiveUiNode.Toggle, is InteractiveUiNode.Collapsible -> InteractiveUiStateKind.Bool
                    is InteractiveUiNode.NumberInput -> InteractiveUiStateKind.Number
                    is InteractiveUiNode.Choice -> if (node.multiple) InteractiveUiStateKind.TextList else InteractiveUiStateKind.Text
                    else -> InteractiveUiStateKind.Text
                }
                reference(bind, "$path.bind", kind)
                if (!InteractiveUiStateRules.acceptsStateValue(retained, bind, payload.state[bind])) fail("$path.bind", InteractiveUiErrorCode.BadValue)
            }
            if (node is InteractiveUiNode.Button) action(node.action, "$path.action")
            if (node is InteractiveUiNode.Carousel) node.items.forEachIndexed { j, item -> item.action?.let { action(it, "$path.items[$j].action") } }
        }
        if (node is InteractiveUiNode.NumberInput) {
            if (node.min != null && node.max != null && node.min > node.max) fail(path, InteractiveUiErrorCode.BadValue)
            if (node.slider && (node.min == null || node.max == null)) fail(path, InteractiveUiErrorCode.BadValue)
        }
        InteractiveUiStateRules.optionValues(node)?.let {
            if (it.toSet().size != it.size) fail("$path.${if (node is InteractiveUiNode.Tabs) "tabs" else "options"}", InteractiveUiErrorCode.DuplicateId)
        }
        if (node is InteractiveUiNode.Chart) {
            var count = 0
            when (val data = node.data) {
                is InteractiveUiNode.ChartData.Categorical -> {
                    if (data.kind == InteractiveUiNode.ChartData.Kind.Pie && data.series.size != 1) fail("$path.series", InteractiveUiErrorCode.BadValue)
                    data.series.forEachIndexed { j, series ->
                        if (series.values.size != data.categories.size) fail("$path.series[$j].values", InteractiveUiErrorCode.BadValue)
                        count += series.values.size
                    }
                }
                is InteractiveUiNode.ChartData.Scatter -> count = data.series.sumOf { it.points.size * 2 }
            }
            if (count > 800) fail("$path.series", InteractiveUiErrorCode.TooMany)
        }
        if (node is InteractiveUiNode.Table) {
            if (node.columns.map { it.key }.toSet().size != node.columns.size) fail("$path.columns", InteractiveUiErrorCode.DuplicateId)
            node.rows.forEachIndexed { j, row ->
                node.columns.filter { it.numeric }.forEach { column ->
                    val value = row[column.key]
                    if (value != null && value != InteractiveUiValue.Null && value !is InteractiveUiValue.Number) {
                        fail("$path.rows[$j].${column.key}", InteractiveUiErrorCode.TypeMismatch)
                    }
                }
            }
        }
        if (node is InteractiveUiNode.Graph) {
            val ids = mutableSetOf<String>()
            node.nodes.forEachIndexed { j, graphNode ->
                if (!ids.add(graphNode.id)) fail("$path.nodes[$j].id", InteractiveUiErrorCode.DuplicateId)
                if (graphNode.layer >= node.layers.size) fail("$path.nodes[$j].layer", InteractiveUiErrorCode.BadValue)
            }
            node.edges.forEachIndexed { j, edge ->
                if (edge.from !in ids) fail("$path.edges[$j].from", InteractiveUiErrorCode.UnknownRef)
                if (edge.to !in ids) fail("$path.edges[$j].to", InteractiveUiErrorCode.UnknownRef)
            }
            if (node.id in reachable) {
                node.bindSelected?.let { key ->
                reference(key, "$path.bind_selected", InteractiveUiStateKind.Text)
                if (!InteractiveUiStateRules.acceptsStateValue(retained, key, payload.state[key])) fail("$path.bind_selected", InteractiveUiErrorCode.BadValue)
            }
            }
        }
    }
    payload.state.forEach { (key, value) ->
        if (!InteractiveUiStateRules.acceptsStateValue(retained, key, value)) {
            fail("state.$key", if (value is InteractiveUiValue.Text && codePointLength(value.value) > InteractiveUiStateRules.stringLimit(retained, key)) {
                InteractiveUiErrorCode.TooLong
            } else {
                InteractiveUiErrorCode.BadValue
            })
        }
    }
    return reachable
}
