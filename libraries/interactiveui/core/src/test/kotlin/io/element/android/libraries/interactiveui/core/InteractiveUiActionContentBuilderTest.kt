/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.core

import io.element.android.libraries.interactiveui.core.action.InteractiveUiActionContentBuilder
import io.element.android.libraries.interactiveui.core.action.InteractiveUiEventContext
import io.element.android.libraries.interactiveui.core.action.InteractiveUiNonce
import io.element.android.libraries.interactiveui.core.protocol.jsJsonString
import io.element.android.libraries.interactiveui.core.protocol.toInteractiveUiValueOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

/**
 * Ported from element-web apps/web/src/interactive-ui/action.test.ts at bcb08017
 * (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial).
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], manifest = org.robolectric.annotation.Config.NONE)
class InteractiveUiActionContentBuilderTest {
    @Test fun `matches actual Web buildActionContent goldens including original IDs threads labels unicode and numbers`() {
        Json.parseToJsonElement(resource("action-goldens.json")).jsonArray.forEach { item ->
            val entry = item.jsonObject
            var payload = parsed(entry.getValue("fixture").jsonPrimitive.content)
            entry["revision"]?.let { payload = payload.copy(revision = it.jsonPrimitive.long) }
            val values = entry.getValue("values").jsonObject.mapValues { it.value.toInteractiveUiValueOrNull()!! }
            val context = InteractiveUiEventContext("${'$'}original", "@bot:example.org", entry["threadRootId"]?.jsonPrimitive?.content)
            val actual = InteractiveUiActionContentBuilder.build(
                context,
                payload,
                "answer",
                values,
                entry.getValue("triggerLabel").jsonPrimitive.content,
                "abcdefghijklmnopqrstuvwxyz012345"
            )
            assertEquals(entry.getValue("name").jsonPrimitive.content, jsJsonString(entry.getValue("expected")), jsJsonString(actual))
        }
    }
    @Test fun `nonce is 24 random bytes encoded as 32 base64url characters`() {
        val seen = mutableSetOf<String>()
        repeat(100) {
            val nonce = InteractiveUiNonce.generate()
            assertTrue(nonce.matches(Regex("[A-Za-z0-9_-]{32}")))
            assertEquals(24, Base64.getUrlDecoder().decode(nonce).size)
            assertTrue(seen.add(nonce))
        }
        val random = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                bytes.fill(0xff.toByte())
            }
        }
        assertEquals("_".repeat(32), InteractiveUiNonce.generate(random))
    }
}
