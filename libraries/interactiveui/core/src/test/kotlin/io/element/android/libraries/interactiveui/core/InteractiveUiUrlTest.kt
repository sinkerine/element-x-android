/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.core

import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiError
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiErrorCode
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiNode
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiParseResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ported from element-web apps/web/src/interactive-ui/protocol.test.ts at bcb08017
 * (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial).
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [24, 33, 35], manifest = org.robolectric.annotation.Config.NONE)
class InteractiveUiUrlTest {
    @Test fun `https parse results agree with canonical Web protocol and schema`() {
        val cases = Json.parseToJsonElement(resource("url-cases.json")).jsonArray
        val mismatches = mutableListOf<String>()
        cases.forEach { entry ->
            val case = entry.jsonObject
            val input = case.getValue("input").jsonPrimitive.content + "a".repeat(case["padding"]?.jsonPrimitive?.int ?: 0)
            val expected = case.getValue("expected")
            val actual = when (val result = parse(linkPayload(input))) {
                is InteractiveUiParseResult.Valid -> buildJsonObject {
                    put("ok", true)
                    put("url", (result.payload.nodes.single() as InteractiveUiNode.Link).url)
                }
                is InteractiveUiParseResult.Invalid -> buildJsonObject {
                    put("ok", false)
                    put("error", buildJsonObject {
                        put("path", result.error.path)
                        put("code", result.error.code.wire)
                    })
                }
            }
            if (expected != actual) mismatches.add("${case.getValue("name")}: Web=$expected Android=$actual")
        }
        assertEquals(emptyList<String>(), mismatches)
    }

    @Test fun `literal host tab keeps schema length as the first error`() {
        val input = "https://exa\tmple.org/" + "a".repeat(2050)
        assertEquals(
            InteractiveUiParseResult.Invalid(InteractiveUiError("nodes[0].url", InteractiveUiErrorCode.TooLong)),
            parse(linkPayload(input))
        )
    }

    @Test fun `parser accepts initial special URL separators and preserves the original url`() {
        for (separators in listOf("\\", "\\\\", "\\/")) {
            for (authority in listOf("example.org/", "example.org:0/", "@example.org/", ":@example.org/", "[::1]/")) {
                val input = "https://$separators$authority"
                val result = parse(linkPayload(input)) as InteractiveUiParseResult.Valid
                assertEquals(input, (result.payload.nodes.single() as InteractiveUiNode.Link).url)
            }
        }
    }

    @Test fun `parser rejects schema violations credentials and bad hosts after initial separators`() {
        val invalidAuthorities = listOf(
            "user@example.org/",
            ":pass@example.org/",
            "user%40name@example.org/",
            "[::gg]/",
            "%5B::1%5D/",
            "%3A%3A1/",
            "exa%3Ample.org/",
            "exa%2Fmple.org/",
            "256.256.256.256/",
            "?example.org",
            "#example.org"
        )
        val inputs = listOf("\\", "\\\\", "\\/", "/\\").flatMap { separators ->
            invalidAuthorities.map { "https://$separators$it" }
        } + listOf(
            "https:///example.org/",
            "https:////example.org/",
            "https:///\\example.org/",
            "https:/\\example.org/",
            "https:\\\\example.org/",
            "https:\\/example.org/"
        )
        inputs.forEach { input ->
            assertEquals(
                InteractiveUiParseResult.Invalid(InteractiveUiError("nodes[0].url", InteractiveUiErrorCode.BadUrl)),
                parse(linkPayload(input))
            )
        }
    }

    private fun linkPayload(input: String): JsonObject {
        val link = buildJsonObject {
            put("id", "link")
            put("type", "link")
            put("label", "Open")
            put("url", input)
        }
        return buildJsonObject {
            put("version", 1)
            put("ui_id", "url_test")
            put("revision", 0)
            put("status", "active")
            put("state", JsonObject(emptyMap()))
            put("root", "link")
            put("nodes", JsonArray(listOf(link)))
        }
    }
}
