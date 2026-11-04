/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.ui.graph

import io.element.android.libraries.interactiveui.ui.model.InteractiveUiBadgeTone
import org.json.JSONArray
import org.json.JSONObject

internal fun graphFixtures(name: String): List<InteractiveUiGraphInput> {
    val json = JSONObject(checkNotNull(InteractiveUiGraphInput::class.java.getResource("/interactive-ui/$name.json")).readText())
    return json.getJSONArray("nodes").objects().filter { it.getString("type") == "graph" }.map { graph ->
        InteractiveUiGraphInput(
            graph.optString("direction") == "top_to_bottom",
            graph.getJSONArray("layers").objects().map { it.getString("label") },
            graph.getJSONArray("nodes").objects().map { node ->
                InteractiveUiGraphInput.Node(
                node.getString("id"),
                    node.getString("label"),
                    node.optString("detail").ifEmpty { null },
                    node.getInt("layer"),
                InteractiveUiBadgeTone.Neutral,
            )
            },
            graph.getJSONArray("edges").objects().map { edge ->
                InteractiveUiGraphInput.Edge(
                edge.getString("from"),
                    edge.getString("to"),
                    edge.optString("label").ifEmpty { null },
                    edge.optString("style") == "dashed",
            )
            }
        )
    }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)

internal fun crossingInput(): InteractiveUiGraphInput = InteractiveUiGraphInput(
    false,
    listOf("A", "B"),
    listOf("a", "b", "c", "d", "e").mapIndexed { i, id ->
        InteractiveUiGraphInput.Node(
            id,
            id,
            null,
        if (i < 2) 0 else 1,
            InteractiveUiBadgeTone.Neutral
        )
    },
    listOf("a" to "d", "b" to "c", "b" to "e").map { (a, b) -> InteractiveUiGraphInput.Edge(a, b, null, false) }
)
