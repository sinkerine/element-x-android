/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.core

import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiError
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiErrorCode
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiParseResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import timber.log.Timber

class InteractiveUiParserRegressionTest {
    @Test fun `schema anchors reject trailing next line in root and node ids`() {
        val root = dividerPayload().with("root", JsonPrimitive("r\u0085"))
        assertInvalid(root, "root", InteractiveUiErrorCode.BadId)
        val node = Json.parseToJsonElement("""{"id":"r\u0085","type":"divider"}""").jsonObject
        assertInvalid(dividerPayload().with("nodes", JsonArray(listOf(node))), "nodes[0].id", InteractiveUiErrorCode.BadId)
        assertInvalid(root.with("nodes", JsonArray(listOf(node))), "root", InteractiveUiErrorCode.BadId)
    }

    @Test fun `schema anchors reject trailing next line in state keys`() {
        val raw = dividerPayload().with("state", JsonObject(mapOf("key\u0085" to JsonNull)))
        assertInvalid(raw, "state.key\u0085", InteractiveUiErrorCode.BadId)
    }

    @Test fun `schema anchors reject trailing next line in action ids`() {
        val node = Json.parseToJsonElement(
            """{"id":"r","type":"button","label":"Submit","action":{"type":"submit","action_id":"send\u0085"}}"""
        )
        assertInvalid(dividerPayload().with("nodes", JsonArray(listOf(node))), "nodes[0].action.action_id", InteractiveUiErrorCode.BadId)
        val form = Json.parseToJsonElement(
            """{"id":"r","type":"form","children":[],"submit_label":"Submit","action_id":"send\u0085"}"""
        )
        assertInvalid(dividerPayload().with("nodes", JsonArray(listOf(form))), "nodes[0].action_id", InteractiveUiErrorCode.BadId)
    }

    @Test fun `schema anchors reject trailing next line in MXC urls`() {
        val node = Json.parseToJsonElement(
            """{"id":"r","type":"image","alt":"Image","mimetype":"image/png","url":"mxc://example.org/media\u0085"}"""
        )
        assertInvalid(dividerPayload().with("nodes", JsonArray(listOf(node))), "nodes[0].url", InteractiveUiErrorCode.BadUrl)
    }

    @Test fun `failure logs contain only trusted codes while results retain hostile keys`() {
        val messages = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                assertEquals(null, t)
                messages.add(message)
            }
        }
        Timber.plant(tree)
        try {
            for ((key, code) in listOf(
                "password=my-secret" to InteractiveUiErrorCode.BadId,
                "token=secret\nforged-log\u0000" to InteractiveUiErrorCode.BadValue
            )) {
                val raw = dividerPayload().with("state", JsonObject(mapOf(key to JsonNull)))
                assertInvalid(raw, "state.$key", code)
            }
            // copyJson can also return a path containing an arbitrary object key before schema validation.
            val key = "password=hidden\r\nforged"
            val raw = dividerPayload().with(key, JsonPrimitive(Double.NaN))
            assertInvalid(raw, key, InteractiveUiErrorCode.BadValue)
            assertEquals(
                listOf("interactive_ui invalid: bad_id", "interactive_ui invalid: bad_value", "interactive_ui invalid: bad_value"),
                messages
            )
        } finally {
            Timber.uproot(tree)
        }
    }

    private fun dividerPayload(): JsonObject = Json.parseToJsonElement(
        """{"version":1,"ui_id":"x","revision":0,"status":"active","state":{},"root":"r","nodes":[{"id":"r","type":"divider"}]}"""
    ).jsonObject

    private fun assertInvalid(raw: JsonObject, path: String, code: InteractiveUiErrorCode) {
        assertEquals(InteractiveUiParseResult.Invalid(InteractiveUiError(path, code)), parse(raw))
    }
}
