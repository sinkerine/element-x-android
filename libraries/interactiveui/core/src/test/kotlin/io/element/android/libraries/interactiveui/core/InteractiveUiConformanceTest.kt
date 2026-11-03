/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.core

import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiConstants
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiErrorCode
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiNode
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiParseResult
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiParser
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from element-web apps/web/src/interactive-ui/protocol.test.ts at bcb08017
 * (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial).
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], manifest = org.robolectric.annotation.Config.NONE)
class InteractiveUiConformanceTest {
    @Test fun `all 22 valid fixtures parse`() {
        val names = fixtureNames("valid")
        assertEquals(22, names.size)
        names.forEach { name -> assertTrue(name, parse(Json.parseToJsonElement(resource("fixtures/valid/$name"))) is InteractiveUiParseResult.Valid) }
    }
    @Test fun `all 53 invalid fixtures match their first error`() {
        val names = fixtureNames("invalid")
        assertEquals(53, names.size)
        names.forEach { name ->
            val expected = Json.parseToJsonElement(resource("fixtures/invalid/${name.removeSuffix(".json")}.expected.json")).jsonObject
            val actual = parse(Json.parseToJsonElement(resource("fixtures/invalid/$name"))) as? InteractiveUiParseResult.Invalid
            assertNotNull(name, actual)
            assertEquals(name, expected["path"]!!.jsonPrimitive.content, actual!!.error.path)
            assertEquals(name, expected["code"]!!.jsonPrimitive.content, actual.error.code.wire)
        }
    }
    @Test fun `trims discriminants and removes unknown fields and unreachable nodes`() {
        val raw = Json.parseToJsonElement(
            """{"version":1,"ui_id":"x","revision":0,"status":"active","state":{"__proto__":"safe"},"root":"root",
                "nodes":[{"id":"unused","type":"divider"},{"id":"root","type":" text ","text":" Trimmed ","extra":1}]}""".trimIndent()
        )
        val payload = (parse(raw) as InteractiveUiParseResult.Valid).payload
        assertEquals(listOf("root"), payload.nodes.map { it.id })
        assertEquals("Trimmed", (payload.nodes.single() as InteractiveUiNode.Text).text)
        assertEquals(InteractiveUiValue.Text("safe"), payload.state["__proto__"])
    }
    @Test fun `unknown node retains only common fields`() {
        val raw = Json.parseToJsonElement(
            """{"version":1,"ui_id":"x","revision":0,"status":"active","state":{},"root":"root",
                "nodes":[{"id":"root","type":"future","children":["absent"],"fallback":"Safe"}]}""".trimIndent()
        )
        val node = (parse(raw) as InteractiveUiParseResult.Valid).payload.nodes.single()
        assertTrue(node is InteractiveUiNode.Unknown)
        assertEquals("Safe", node.fallback)
    }
    @Test fun `edits select latest new content and absent content stays absent`() {
        val content = JsonObject(mapOf(InteractiveUiConstants.PAYLOAD_KEY to fixture("form")))
        val original = JsonObject(mapOf("type" to JsonPrimitive("m.room.message"), "content" to content)).toString()
        val edit = JsonObject(mapOf("type" to JsonPrimitive("m.room.message"), "content" to JsonObject(mapOf("m.new_content" to content)))).toString()
        assertTrue(InteractiveUiParser.parseEvent(original, edit) is InteractiveUiParseResult.Valid)
        assertNull(InteractiveUiParser.parseEvent(original, """{"type":"m.room.message","content":{"m.new_content":{"body":"edited"}}}"""))
        assertNull(InteractiveUiParser.parseEvent(original, "bad json"))
        assertNull(InteractiveUiParser.parseEvent(original.replace("m.room.message", "m.room.topic"), null))
        assertNull(InteractiveUiParser.parseEvent(null, null))
        assertNull(InteractiveUiParser.parse(JsonObject(emptyMap())))
        assertEquals(
            InteractiveUiErrorCode.TooLarge,
            (InteractiveUiParser.parseEvent("x".repeat(262_145), null) as InteractiveUiParseResult.Invalid).error.code
        )
    }
}
