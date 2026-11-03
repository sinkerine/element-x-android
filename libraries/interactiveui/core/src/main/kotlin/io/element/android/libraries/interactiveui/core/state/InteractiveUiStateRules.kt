/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.core.state

import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiAction
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiCondition
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiNode
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiPayload
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiStatus
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiValue
import io.element.android.libraries.interactiveui.core.protocol.codePointLength
import io.element.android.libraries.interactiveui.core.protocol.hasForbiddenControls
import io.element.android.libraries.interactiveui.core.protocol.isSafeInteger
import io.element.android.libraries.interactiveui.core.protocol.jsTrim
import io.element.android.libraries.interactiveui.core.protocol.toInteractiveUiValueOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Ported from element-web apps/web/src/interactive-ui/state.ts at bcb08017 (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial). */
enum class InteractiveUiStateKind { Text, Number, Bool, TextList, Null }
sealed interface InteractiveUiSubmission {
    data class Valid(val actionId: String, val values: Map<String, InteractiveUiValue>) : InteractiveUiSubmission
    data class Invalid(val invalidKeys: Set<String>) : InteractiveUiSubmission
}
object InteractiveUiStateRules {
    fun stateKind(payload: InteractiveUiPayload, key: String): InteractiveUiStateKind {
        val value = payload.state[key]
        if (value != InteractiveUiValue.Null) return kind(value)
        for (node in payload.nodes) {
            if (node is InteractiveUiNode.Graph && node.bindSelected == key) return InteractiveUiStateKind.Text
            if (binding(node) == key) {
                return when (node) {
                    is InteractiveUiNode.NumberInput -> InteractiveUiStateKind.Number
                    is InteractiveUiNode.Toggle, is InteractiveUiNode.Collapsible -> InteractiveUiStateKind.Bool
                    is InteractiveUiNode.Choice -> if (node.multiple) InteractiveUiStateKind.TextList else InteractiveUiStateKind.Text
                    else -> InteractiveUiStateKind.Text
                }
            }
        }
        return InteractiveUiStateKind.Null
    }

    fun acceptsStateValue(payload: InteractiveUiPayload, key: String, value: InteractiveUiValue?): Boolean {
        if (key !in payload.state || value == null) return false
        val expected = stateKind(payload, key)
        if (value == InteractiveUiValue.Null) return expected in listOf(InteractiveUiStateKind.Text, InteractiveUiStateKind.Number, InteractiveUiStateKind.Null)
        if (kind(value) != expected) return false
        if (value is InteractiveUiValue.Number && !value.value.isFinite()) return false
        if (value is InteractiveUiValue.Text && (codePointLength(value.value) > stringLimit(payload, key) || hasForbiddenControls(value.value))) return false
        if (value is InteractiveUiValue.TextList) {
            if (value.values.size > 50 || value.values.toSet().size != value.values.size) return false
            if (value.values.any { codePointLength(it) > 200 || hasForbiddenControls(it) }) return false
        }
        for (node in payload.nodes) {
            if (binding(node) != key) continue
            val options = optionValues(node)
            if (options != null) {
                val values = if (value is InteractiveUiValue.TextList) value.values else listOf((value as? InteractiveUiValue.Text)?.value)
                if (values.any { it !in options }) return false
            }
            if (node is InteractiveUiNode.TextInput && (value !is InteractiveUiValue.Text || codePointLength(value.value) > node.maxLength)) return false
            if (node is InteractiveUiNode.NumberInput) {
                if (value !is InteractiveUiValue.Number) return false
                if (node.min?.let { value.value < it } == true || node.max?.let { value.value > it } == true) return false
            }
        }
        val graphs = payload.nodes.filterIsInstance<InteractiveUiNode.Graph>().filter { it.bindSelected == key }
        if (graphs.isNotEmpty() && graphs.none { graph -> graph.nodes.any { InteractiveUiValue.Text(it.id) == value } }) return false
        return true
    }

    fun initialState(payload: InteractiveUiPayload): Map<String, InteractiveUiValue> = payload.state.mapValues { copyValue(it.value) }

    fun mergeSavedState(payload: InteractiveUiPayload, saved: JsonElement?): Map<String, InteractiveUiValue> {
        val state = initialState(payload).toMutableMap()
        try {
            if (saved !is JsonObject) return state
            var values = saved
            if (listOf("ui_id", "revision", "state").all { it in saved } && saved["state"] is JsonObject) {
                val revision = (saved["revision"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
                if ((saved["ui_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content != payload.uiId) return state
                if (revision == null || !isSafeInteger(revision)) return state
                if (revision < 0 || revision > payload.revision.toDouble()) return state
                values = saved.getValue("state") as JsonObject
            }
            for (key in state.keys.toList()) {
                val value = values[key]?.toInteractiveUiValueOrNull()
                if (value != null && acceptsStateValue(payload, key, value)) state[key] = copyValue(value)
            }
        } catch (_: Throwable) {
            // Corrupt persisted values must not prevent rendering.
        }
        return state
    }

    fun evaluateCondition(condition: InteractiveUiCondition, state: Map<String, InteractiveUiValue>): Boolean {
        val value = state[condition.stateKey]
        // Wire comparisons are scalars. JS never compares arrays structurally.
        val matched = value != null && value !is InteractiveUiValue.TextList && when (val comparison = condition.comparison) {
            is InteractiveUiCondition.Comparison.EqualTo -> value == comparison.value
            is InteractiveUiCondition.Comparison.OneOf -> value in comparison.values
        }
        return if (condition.negate) !matched else matched
    }

    fun validateSubmission(payload: InteractiveUiPayload, nodeId: String, state: Map<String, InteractiveUiValue>,
        carouselItemId: String? = null): InteractiveUiSubmission {
        val node = payload.nodesById[nodeId]
        if (node == null || payload.status != InteractiveUiStatus.Active) return InteractiveUiSubmission.Invalid(emptySet())
        if (carouselItemId != null && node !is InteractiveUiNode.Carousel) return InteractiveUiSubmission.Invalid(emptySet())
        val keys = linkedSetOf<String>()
        val actionId: String
        if (node is InteractiveUiNode.Form) {
            actionId = node.actionId
            val pending = node.children.toMutableList()
            val visited = mutableSetOf<String>()
            while (pending.isNotEmpty()) {
                val id = pending.removeAt(pending.lastIndex)
                if (!visited.add(id)) continue
                payload.nodesById[id]?.let { descendant ->
                    binding(descendant)?.let(keys::add)
                    pending.addAll(childIds(descendant))
                }
            }
        } else {
            val action = when (node) {
                is InteractiveUiNode.Button -> node.action
                is InteractiveUiNode.Carousel -> node.items.firstOrNull { it.id == carouselItemId }?.action
                else -> null
            }
            if (action !is InteractiveUiAction.Submit) return InteractiveUiSubmission.Invalid(emptySet())
            actionId = action.actionId
            keys.addAll(action.include)
        }
        val invalid = linkedSetOf<String>()
        val values = linkedMapOf<String, InteractiveUiValue>()
        for (key in keys) {
            val value = state[key]
            if (!acceptsStateValue(payload, key, value)) {
                invalid.add(key)
                continue
            }
            val required = payload.nodes.filterIsInstance<InteractiveUiNode.TextInput>().any { it.bind == key && it.required }
            if (required && (value !is InteractiveUiValue.Text || jsTrim(value.value).isEmpty())) invalid.add(key)
            values[key] = copyValue(value!!)
        }
        return if (invalid.isEmpty()) InteractiveUiSubmission.Valid(actionId, values) else InteractiveUiSubmission.Invalid(invalid)
    }

    fun childIds(node: InteractiveUiNode): List<String> = when (node) {
        is InteractiveUiNode.Stack -> node.children
        is InteractiveUiNode.Card -> node.children
        is InteractiveUiNode.Collapsible -> node.children
        is InteractiveUiNode.Form -> node.children
        is InteractiveUiNode.Tabs -> node.tabs.map { it.child }
        else -> emptyList()
    }

    internal fun binding(node: InteractiveUiNode): String? = when (node) {
        is InteractiveUiNode.Tabs -> node.bind
        is InteractiveUiNode.Collapsible -> node.bind
        is InteractiveUiNode.Select -> node.bind
        is InteractiveUiNode.Choice -> node.bind
        is InteractiveUiNode.Toggle -> node.bind
        is InteractiveUiNode.TextInput -> node.bind
        is InteractiveUiNode.NumberInput -> node.bind
        else -> null
    }
    internal fun optionValues(node: InteractiveUiNode): List<String>? = when (node) {
        is InteractiveUiNode.Tabs -> node.tabs.map { it.value }
        is InteractiveUiNode.Select -> node.options.map { it.value }
        is InteractiveUiNode.Choice -> node.options.map { it.value }
        else -> null
    }
    internal fun stringLimit(payload: InteractiveUiPayload, key: String): Int = payload.nodes.filterIsInstance<InteractiveUiNode.TextInput>()
        .filter { it.bind == key }
        .minOfOrNull { it.maxLength }
        ?: 200
    private fun copyValue(value: InteractiveUiValue): InteractiveUiValue = if (value is InteractiveUiValue.TextList) {
        value.copy(
            values = value.values.toMutableList()
        )
    } else {
        value
    }
    private fun kind(value: InteractiveUiValue?): InteractiveUiStateKind = when (value) {
        is InteractiveUiValue.Text -> InteractiveUiStateKind.Text
        is InteractiveUiValue.Number -> InteractiveUiStateKind.Number
        is InteractiveUiValue.Bool -> InteractiveUiStateKind.Bool
        is InteractiveUiValue.TextList -> InteractiveUiStateKind.TextList
        else -> InteractiveUiStateKind.Null
    }
}
