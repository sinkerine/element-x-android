/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.core.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Ported from element-web apps/web/src/interactive-ui/protocol.ts at bcb08017 (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial). */
internal fun decodePayload(value: JsonObject): InteractiveUiPayload = InteractiveUiPayload(
    uiId = value.text("ui_id"),
    revision = value.number("revision")!!.toLong(),
    status = enumValue(value.text("status")),
    statusText = value.string("status_text"),
    title = value.string("title"),
    state = value.getValue("state").jsonObject.mapValues { it.value.toInteractiveUiValueOrNull()!! },
    root = value.text("root"),
    nodes = value.objects("nodes").map(::decodeNode),
)

private fun decodeCondition(value: JsonObject): InteractiveUiCondition = InteractiveUiCondition(
    stateKey = value.text("state"),
    negate = value.flag("not"),
    comparison = if ("equals" in value) {
        InteractiveUiCondition.Comparison.EqualTo(value.getValue("equals").toInteractiveUiValueOrNull()!!)
    } else {
        InteractiveUiCondition.Comparison.OneOf(value.getValue("in").jsonArray.map { it.toInteractiveUiValueOrNull()!! })
    },
)

private fun decodeAction(value: JsonObject): InteractiveUiAction = when (value.text("type")) {
    "set_state" -> InteractiveUiAction.SetState(value.text("key"), value.getValue("value").toInteractiveUiValueOrNull()!!)
    "toggle_state" -> InteractiveUiAction.ToggleState(value.text("key"))
    "submit" -> InteractiveUiAction.Submit(value.text("action_id"), value.strings("include"), value.flag("single_use"))
    else -> InteractiveUiAction.OpenUrl(value.text("url"))
}

private fun decodeImage(value: JsonObject) = InteractiveUiImage(
    alt = value.text("alt"),
    mxcUrl = value.string("url"),
    encryptedFile = value["file"] as? JsonObject,
    mimetype = value.text("mimetype"),
    width = value.number("width")?.toInt(),
    height = value.number("height")?.toInt(),
)

private fun decodeNode(value: JsonObject): InteractiveUiNode {
    val id = value.text("id")
    val visible = (value["visible_when"] as? JsonObject)?.let(::decodeCondition)
    val fallback = value.string("fallback")
    val children = value.strings("children")
    val label = value.string("label").orEmpty()
    val bind = value.string("bind").orEmpty()
    val options = value.objects("options").map { InteractiveUiOption(it.text("value"), it.text("label")) }
    return when (value.text("type")) {
        "stack" -> InteractiveUiNode.Stack(id, visible, fallback, children, enumValue(value.text("direction")), enumValue(value.string("gap") ?: "md"))
        "card" -> InteractiveUiNode.Card(id, visible, fallback, value.string("title"), children)
        "tabs" -> InteractiveUiNode.Tabs(
            id,
            visible,
            fallback,
            bind,
            value.objects("tabs").map { InteractiveUiNode.Tabs.Tab(it.text("value"), it.text("label"), it.text("child")) }
        )
        "collapsible" -> InteractiveUiNode.Collapsible(id, visible, fallback, label, children, value.string("bind"))
        "divider" -> InteractiveUiNode.Divider(id, visible, fallback)
        "heading" -> InteractiveUiNode.Heading(id, visible, fallback, value.text("text"), value.number("level")!!.toInt())
        "text" -> InteractiveUiNode.Text(
            id,
            visible,
            fallback,
            value.text("text"),
            enumValue(value.string("tone") ?: "default"),
            enumValue(value.string("size") ?: "body")
        )
        "rich_text" -> InteractiveUiNode.RichText(id, visible, fallback, value.text("text"), value.string("html"))
        "badge" -> InteractiveUiNode.Badge(id, visible, fallback, value.text("text"), enumValue(value.string("tone") ?: "neutral"))
        "key_value" -> InteractiveUiNode.KeyValue(id, visible, fallback, value.objects("items").map { it.text("label") to it.text("value") })
        "list" -> InteractiveUiNode.ItemList(
            id,
            visible,
            fallback,
            value.objects("items").map { InteractiveUiNode.ItemList.Item(it.text("text"), it.string("secondary")) },
            value.flag("ordered")
        )
        "image" -> InteractiveUiNode.Image(id, visible, fallback, decodeImage(value))
        "carousel" -> InteractiveUiNode.Carousel(id, visible, fallback, label, value.objects("items").map { item ->
            val badge = item["badge"] as? JsonObject
            InteractiveUiNode.Carousel.Item(
                item.text("id"),
                decodeImage(item.getValue("image").jsonObject),
                item.text("title"),
                item.string("subtitle"),
                item.strings("meta"),
                badge?.let { it.text("text") to enumValue<InteractiveUiTone>(it.string("tone") ?: "neutral") },
                (item["action"] as? JsonObject)?.let(::decodeAction)
            )
        })
        "link" -> InteractiveUiNode.Link(id, visible, fallback, label, value.text("url"), enumValue(value.string("kind") ?: "external"))
        "table" -> InteractiveUiNode.Table(id, visible, fallback, value.string("caption"), value.objects("columns").map {
            InteractiveUiNode.Table.Column(it.text("key"), it.text("label"), it.string("kind") == "number", it.string("align") == "end")
        }, value.objects("rows").map { row -> row.mapValues { it.value.toInteractiveUiValueOrNull()!! } }, value.flag("sortable"))
        "chart" -> InteractiveUiNode.Chart(
            id,
            visible,
            fallback,
            value.string("title"),
            value.string("x_label"),
            value.string("y_label"),
            value.string("unit"),
            if (value.text("kind") == "scatter") {
                InteractiveUiNode.ChartData.Scatter(value.objects("series").map { series ->
                InteractiveUiNode.ChartData.ScatterSeries(series.text("name"), series.objects("points").map {
                    InteractiveUiNode.ChartData.Point(it.number("x")!!, it.number("y")!!, it.string("label"))
                })
            })
            } else {
                InteractiveUiNode.ChartData.Categorical(enumValue(value.text("kind")), value.strings("categories"), value.objects("series").map {
                InteractiveUiNode.ChartData.Series(it.text("name"), it.getValue("values").jsonArray.map { number -> number.jsonPrimitive.double })
            })
            }
        )
        "graph" -> InteractiveUiNode.Graph(
            id,
            visible,
            fallback,
            value.string("direction") == "top_to_bottom",
            value.objects("layers").map { it.text("label") },
            value.objects("nodes").map {
                InteractiveUiNode.Graph.GraphNode(
                    it.text("id"),
                    it.text("label"),
                    it.string("detail"),
                    it.number("layer")!!.toInt(),
                    enumValue(it.string("tone") ?: "neutral")
                )
            },
            value.objects("edges").map {
                InteractiveUiNode.Graph.GraphEdge(it.text("from"), it.text("to"), it.string("label"), it.string("style") == "dashed")
            },
            value.string("bind_selected")
        )
        "button" -> InteractiveUiNode.Button(
            id,
            visible,
            fallback,
            label,
            enumValue(value.string("variant") ?: "secondary"),
            decodeAction(value.getValue("action").jsonObject)
        )
        "select" -> InteractiveUiNode.Select(id, visible, fallback, label, bind, options)
        "choice" -> InteractiveUiNode.Choice(id, visible, fallback, label, bind, options, value.flag("multiple"))
        "toggle" -> InteractiveUiNode.Toggle(id, visible, fallback, label, bind)
        "text_input" -> InteractiveUiNode.TextInput(
            id,
            visible,
            fallback,
            label,
            bind,
            value.flag("multiline"),
            value.string("placeholder"),
            value.number("max_length")!!.toInt(),
            value.flag("required")
        )
        "number_input" -> InteractiveUiNode.NumberInput(
            id,
            visible,
            fallback,
            label,
            bind,
            value.number("min"),
            value.number("max"),
            value.number("step"),
            value.string("display") == "slider"
        )
        "form" -> InteractiveUiNode.Form(id, visible, fallback, children, value.text("submit_label"), value.text("action_id"), value.flag("single_use", true))
        else -> InteractiveUiNode.Unknown(id, visible, fallback)
    }
}
private fun JsonObject.text(key: String): String = string(key)!!
private fun JsonObject.flag(key: String, default: Boolean = false): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: default
private fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
private fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray)?.map { it.jsonObject }.orEmpty()
private inline fun <reified T : Enum<T>> enumValue(wire: String): T = enumValues<T>().first { it.name.equals(wire, ignoreCase = true) }
