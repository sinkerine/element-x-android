/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.core.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Ported from element-web apps/web/src/interactive-ui/protocol.ts at bcb08017 (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial). */
internal fun field(path: String, key: String): String = if (path.isEmpty()) key else "$path.$key"
internal fun fail(path: String, code: InteractiveUiErrorCode): Nothing = throw InteractiveUiValidationException(InteractiveUiError(path, code))
internal class InteractiveUiValidationException(val error: InteractiveUiError) : RuntimeException(null, null, false, false)

/** Measures the JS JSON representation, including ignored properties, before schema validation. */
internal fun copyJson(input: JsonElement): JsonElement {
    var bytes = 0
    var visits = 0
    val ancestors = java.util.IdentityHashMap<JsonElement, Boolean>()
    fun count(n: Int) {
        bytes += n
        if (bytes > InteractiveUiConstants.CLIENT_MAX_PAYLOAD_BYTES) fail("", InteractiveUiErrorCode.TooLarge)
    }
    fun stringBytes(value: String): Int {
        if (value.length > 60_000) fail("", InteractiveUiErrorCode.TooLarge)
        // JSON.stringify escapes lone surrogates and controls; valid pairs encode as four UTF-8 bytes.
        var size = 2
        var index = 0
        while (index < value.length) {
            val c = value[index++]
            size += when {
                c == '"' || c == '\\' || c in listOf('\b', '\t', '\n', '\u000c', '\r') -> 2
                c < ' ' -> 6
                c.isHighSurrogate() && index < value.length && value[index].isLowSurrogate() -> {
                    index++
                    4
                }
                c.isSurrogate() -> 6
                c.code < 128 -> 1
                c.code < 2048 -> 2
                else -> 3
            }
        }
        return size
    }
    fun copy(value: JsonElement, path: String, depth: Int): JsonElement {
        if (++visits > 60_000 || depth > 64) fail(path, InteractiveUiErrorCode.TooLarge)
        return when (value) {
            JsonNull -> value.also { count(4) }
            is JsonPrimitive -> value.also {
                when {
                    value.isString -> count(stringBytes(value.content))
                    value.booleanOrNull != null -> count(if (value.booleanOrNull == true) 4 else 5)
                    else -> {
                        val number = value.doubleOrNull
                        if (number == null || !number.isFinite()) fail(path, InteractiveUiErrorCode.BadValue)
                        count(jsNumberToString(number).length)
                    }
                }
            }
            is JsonArray -> {
                if (ancestors.put(value, true) != null) fail(path, InteractiveUiErrorCode.Cycle)
                if (value.size > 30_000) fail(path, InteractiveUiErrorCode.TooLarge)
                count(2)
                JsonArray(value.mapIndexed { index, child ->
                    count(if (index > 0) 1 else 0)
                    copy(child, "$path[$index]", depth + 1)
                }).also { ancestors.remove(value) }
            }
            is JsonObject -> {
                if (ancestors.put(value, true) != null) fail(path, InteractiveUiErrorCode.Cycle)
                if (value.size > 30_000) fail(path, InteractiveUiErrorCode.TooLarge)
                count(2)
                JsonObject(jsKeyOrder(value.keys).mapIndexed { index, key ->
                    count((if (index > 0) 1 else 0) + stringBytes(key) + 1)
                    key to copy(value.getValue(key), field(path, key), depth + 1)
                }.toMap()).also { ancestors.remove(value) }
            }
        }
    }
    return copy(input, "", 0)
}
