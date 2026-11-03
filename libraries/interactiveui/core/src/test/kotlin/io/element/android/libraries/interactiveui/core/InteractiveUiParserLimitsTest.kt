/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.core

import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiErrorCode
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiParseResult
import io.element.android.libraries.interactiveui.core.protocol.copyJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from element-web apps/web/src/interactive-ui/protocol.test.ts at bcb08017
 * (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial).
 */
class InteractiveUiParserLimitsTest {
    @Test fun `exact UTF8 byte boundary and 300 nodes parse under 50 milliseconds`() {
        val nodes = mutableListOf<JsonElement>(buildJsonObject {
            put("id", "root")
            put("type", "stack")
            put("direction", "vertical")
            put("children", JsonArray((0..298).map { JsonPrimitive("n$it") }))
        })
        nodes.addAll((0..298).map {
            buildJsonObject {
            put("id", "n$it")
            put("type", "divider")
        }
        })
        var raw = buildJsonObject {
            put("version", 1)
            put("ui_id", "bounded")
            put("revision", 0)
            put("status", "active")
            put("state", JsonObject(emptyMap()))
            put("root", "root")
            put("nodes", JsonArray(nodes))
            put("extra", "")
        }
        val padding = "x".repeat(60_000 - raw.toString().toByteArray().size)
        raw = raw.with("extra", JsonPrimitive(padding))
        assertEquals(60_000, raw.toString().toByteArray().size)
        repeat(5) { assertTrue(parse(raw) is InteractiveUiParseResult.Valid) }
        val start = System.nanoTime()
        assertTrue(parse(raw) is InteractiveUiParseResult.Valid)
        val millis = (System.nanoTime() - start) / 1_000_000.0
        assertTrue("Parsing took $millis ms", millis < 50)
        println("60000 bytes / 300 nodes: $millis ms")
        assertEquals(InteractiveUiErrorCode.TooLarge, (parse(raw.with("extra", JsonPrimitive(padding + "x"))) as InteractiveUiParseResult.Invalid).error.code)
        assertEquals(
            InteractiveUiErrorCode.TooLarge,
            (parse(raw.with("extra", JsonPrimitive("中".repeat(20_000)))) as InteractiveUiParseResult.Invalid).error.code
        )
    }
    @Test fun `rejects nonfinite numbers and excessive JSON nesting before schema`() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(InteractiveUiErrorCode.BadValue, (parse(JsonPrimitive(value)) as InteractiveUiParseResult.Invalid).error.code)
        }
        var nested: JsonElement = JsonPrimitive(true)
        repeat(66) { nested = JsonArray(listOf(nested)) }
        assertEquals(InteractiveUiErrorCode.TooLarge, (parse(nested) as InteractiveUiParseResult.Invalid).error.code)
    }
    @Test fun `mutable backing cycles are detected by identity and repeated siblings remain legal`() {
        val map = mutableMapOf<String, JsonElement>()
        val cyclic = JsonObject(map)
        map["self"] = cyclic
        val failure = parse(cyclic) as InteractiveUiParseResult.Invalid
        assertEquals(InteractiveUiErrorCode.Cycle, failure.error.code)
        assertEquals("self", failure.error.path)
        val list = mutableListOf<JsonElement>()
        val array = JsonArray(list)
        list.add(array)
        assertEquals(InteractiveUiErrorCode.Cycle, (parse(array) as InteractiveUiParseResult.Invalid).error.code)
        val child = JsonObject(emptyMap())
        assertEquals(JsonArray(listOf(child, child)), copyJson(JsonArray(listOf(child, child))))
    }
    @Test fun `uses JS number lengths and lone surrogate byte lengths`() {
        assertEquals(JsonPrimitive(1e21), copyJson(JsonPrimitive(1e21)))
        assertEquals(JsonPrimitive("\ud800"), copyJson(JsonPrimitive("\ud800")))
        assertEquals(JsonPrimitive("😀"), copyJson(JsonPrimitive("😀")))
    }
}
