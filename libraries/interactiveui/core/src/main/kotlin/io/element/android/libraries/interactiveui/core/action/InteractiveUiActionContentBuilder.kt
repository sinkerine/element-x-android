/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.core.action

import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiConstants
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiNode
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiPayload
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiValue
import io.element.android.libraries.interactiveui.core.protocol.codePointLength
import io.element.android.libraries.interactiveui.core.protocol.jsKeyOrder
import io.element.android.libraries.interactiveui.core.protocol.jsNumberToString
import io.element.android.libraries.interactiveui.core.protocol.toJsonElement
import io.element.android.libraries.interactiveui.core.state.InteractiveUiStateRules
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Ported from element-web apps/web/src/interactive-ui/action.ts at bcb08017 (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial). */
data class InteractiveUiEventContext(val eventId: String, val senderId: String, val threadRootId: String?)
object InteractiveUiActionContentBuilder {
    fun build(context: InteractiveUiEventContext, payload: InteractiveUiPayload, actionId: String,
        values: Map<String, InteractiveUiValue>, triggerLabel: String, nonce: String): JsonObject {
        val labels = mutableMapOf<String, String>()
        val valueLabels = mutableMapOf<String, MutableMap<String, String>>()
        for (node in payload.nodes) {
            val key = InteractiveUiStateRules.binding(node) ?: (node as? InteractiveUiNode.Graph)?.bindSelected ?: continue
            val label = when (node) {
                is InteractiveUiNode.Collapsible -> node.label
                is InteractiveUiNode.Select -> node.label
                is InteractiveUiNode.Choice -> node.label
                is InteractiveUiNode.Toggle -> node.label
                is InteractiveUiNode.TextInput -> node.label
                is InteractiveUiNode.NumberInput -> node.label
                else -> null
            }
            if (label != null) labels[key] = label
            val options = when (node) {
                is InteractiveUiNode.Tabs -> node.tabs.map { it.value to it.label }
                is InteractiveUiNode.Select -> node.options.map { it.value to it.label }
                is InteractiveUiNode.Choice -> node.options.map { it.value to it.label }
                else -> null
            }
            if (options != null) valueLabels[key] = options.toMap().toMutableMap()
            if (node is InteractiveUiNode.Graph) {
                val entries = valueLabels.getOrPut(key) { mutableMapOf() }
                node.nodes.forEach { entries[it.id] = it.label }
            }
        }
        val descriptions = jsKeyOrder(values.keys).map { key ->
            fun describe(value: InteractiveUiValue): String = when (value) {
                is InteractiveUiValue.Text -> valueLabels[key]?.get(value.value) ?: value.value
                InteractiveUiValue.Null -> "—"
                is InteractiveUiValue.Number -> jsNumberToString(value.value)
                is InteractiveUiValue.Bool -> value.value.toString()
                is InteractiveUiValue.TextList -> value.values.joinToString(", ") { valueLabels[key]?.get(it) ?: it }
            }
            "${labels[key] ?: key}: ${describe(values.getValue(key))}"
        }
        val body = "“$triggerLabel”" + if (descriptions.isNotEmpty()) " — ${descriptions.joinToString("; ")}" else ""
        val boundedBody = body.substring(0, body.offsetByCodePoints(0, codePointLength(body).coerceAtMost(1000)))
        return buildJsonObject {
            put("msgtype", "m.text")
            put("body", boundedBody)
            putJsonObject("m.mentions") {
                put("user_ids", JsonArray(if (context.senderId.isEmpty()) emptyList() else listOf(JsonPrimitive(context.senderId))))
            }
            putJsonObject(InteractiveUiConstants.ACTION_KEY) {
                put("version", InteractiveUiConstants.VERSION)
                put("ui_event_id", context.eventId)
                put("ui_id", payload.uiId)
                put("revision", payload.revision)
                put("action_id", actionId)
                put("values", JsonObject(jsKeyOrder(values.keys).associateWith { values.getValue(it).toJsonElement() }))
                put("nonce", nonce)
            }
            putJsonObject("m.relates_to") {
                if (!context.threadRootId.isNullOrEmpty()) {
                    put("rel_type", "m.thread")
                    put("event_id", context.threadRootId)
                }
                putJsonObject("m.in_reply_to") { put("event_id", context.eventId) }
                if (!context.threadRootId.isNullOrEmpty()) put("is_falling_back", false)
            }
        }
    }
}
