/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.core

import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiValue
import io.element.android.libraries.interactiveui.core.protocol.codePointLength
import io.element.android.libraries.interactiveui.core.protocol.isSafeInteger
import io.element.android.libraries.interactiveui.core.protocol.jsKeyOrder
import io.element.android.libraries.interactiveui.core.protocol.jsNumberToString
import io.element.android.libraries.interactiveui.core.protocol.jsTrim
import io.element.android.libraries.interactiveui.core.protocol.schemaRegex
import io.element.android.libraries.interactiveui.core.protocol.toInteractiveUiValueOrNull
import io.element.android.libraries.interactiveui.core.protocol.toJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from element-web apps/web/src/interactive-ui/protocol.test.ts at bcb08017
 * (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial).
 */
class JsSemanticsTest {
    @Test fun `trim uses exactly ECMAScript whitespace`() {
        val whitespace = "\u0009\u000a\u000b\u000c\u000d \u00a0\u1680\u2000\u2001\u2002\u2003\u2004\u2005" +
            "\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000\ufeff"
        assertEquals("value", jsTrim(whitespace + "value" + whitespace))
        for (char in listOf('\u0085', '\u180e', '\u001c', '\u200b')) assertEquals("${char}x$char", jsTrim("${char}x$char"))
        assertEquals(2, codePointLength("中😀"))
    }
    @Test fun `schema regex translates terminal anchors without changing escaped dollars or substring matching`() {
        assertTrue(schemaRegex("^[a-z]+$").containsMatchIn("r"))
        assertFalse(schemaRegex("^[a-z]+$").containsMatchIn("r\u0085"))
        assertFalse(schemaRegex("^[a-z]+$").containsMatchIn("r\n"))
        assertTrue(schemaRegex("r\\$").containsMatchIn("r$ suffix"))
        assertTrue(schemaRegex("r[$]").containsMatchIn("r$ suffix"))
        assertTrue(schemaRegex("r\\\\$").containsMatchIn("r\\"))
        assertFalse(schemaRegex("r\\\\$").containsMatchIn("r\\\u0085"))
        assertTrue(schemaRegex("[a-z]+").containsMatchIn("123r456"))
    }
    @Test fun `integer property keys precede ordinary insertion order`() {
        assertEquals(
            listOf("0", "2", "10", "4294967294", "01", "4294967295", "-1", "x"),
            jsKeyOrder(listOf("01", "10", "4294967295", "2", "-1", "0", "4294967294", "x"))
        )
        assertTrue(isSafeInteger(9_007_199_254_740_991.0))
        assertFalse(isSafeInteger(9_007_199_254_740_992.0))
        assertFalse(isSafeInteger(1.5))
        assertFalse(isSafeInteger(Double.NaN))
    }
    @Test fun `number formatting agrees with Node on boundaries and 2000 generated numbers`() {
        Json.parseToJsonElement(resource("number-cases.json")).jsonArray.forEach { item ->
            val value = item.jsonObject.getValue("value").jsonPrimitive.double
            assertEquals(value.toString(), item.jsonObject.getValue("expected").jsonPrimitive.content, jsNumberToString(value))
        }
    }
    @Test fun `numeric equality uses JS strict equality and normalizes signed zero hash`() {
        assertEquals(InteractiveUiValue.Number(0.0), InteractiveUiValue.Number(-0.0))
        assertEquals(InteractiveUiValue.Number(0.0).hashCode(), InteractiveUiValue.Number(-0.0).hashCode())
        assertNotEquals(InteractiveUiValue.Number(1.0), InteractiveUiValue.Text("1"))
        assertNull(JsonPrimitive(Double.POSITIVE_INFINITY).toInteractiveUiValueOrNull())
        assertNull(JsonArray(listOf(JsonPrimitive(1))).toInteractiveUiValueOrNull())
        val values = listOf(
            InteractiveUiValue.Text("hello"),
            InteractiveUiValue.Number(1.5),
            InteractiveUiValue.Bool(true),
            InteractiveUiValue.TextList(listOf("a", "b")),
            InteractiveUiValue.Null
        )
        values.forEach { assertEquals(it, it.toJsonElement().toInteractiveUiValueOrNull()) }
    }
}
