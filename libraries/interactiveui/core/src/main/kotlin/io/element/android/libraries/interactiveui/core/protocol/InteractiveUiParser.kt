/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.core.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import timber.log.Timber

/** Ported from element-web apps/web/src/interactive-ui/protocol.ts at bcb08017 (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial). */
sealed interface InteractiveUiParseResult {
    data class Valid(val payload: InteractiveUiPayload) : InteractiveUiParseResult
    data class Invalid(val error: InteractiveUiError) : InteractiveUiParseResult
}
object InteractiveUiParser {
    fun parse(content: JsonObject): InteractiveUiParseResult? {
        if (InteractiveUiConstants.PAYLOAD_KEY !in content) return null
        return try {
            val raw = copyJson(content.getValue(InteractiveUiConstants.PAYLOAD_KEY))
            if (raw !is JsonObject) fail("", InteractiveUiErrorCode.NotObject)
            val version = raw["version"] as? JsonPrimitive
            if (version == null || version.isString || version.doubleOrNull != 1.0) fail("version", InteractiveUiErrorCode.BadVersion)
            val payload = decodePayload(InteractiveUiSchema.V1.read(raw).jsonObject)
            val reachable = validateRelations(payload)
            InteractiveUiParseResult.Valid(payload.copy(nodes = payload.nodes.filter { it.id in reachable }))
        } catch (error: Throwable) {
            invalid((error as? InteractiveUiValidationException)?.error ?: InteractiveUiError("", InteractiveUiErrorCode.BadType))
        }
    }

    fun parseEvent(originalJson: String?, latestEditJson: String?): InteractiveUiParseResult? {
        if (originalJson?.length?.let { it > 262_144 } == true || latestEditJson?.length?.let { it > 262_144 } == true) {
            return invalid(InteractiveUiError("", InteractiveUiErrorCode.TooLarge))
        }
        return try {
            val selected = latestEditJson ?: originalJson ?: return null
            val event = Json.parseToJsonElement(selected) as? JsonObject ?: return null
            if (event.string("type") != "m.room.message") return null
            val content = event["content"] as? JsonObject ?: return null
            val actual = if (latestEditJson != null) content["m.new_content"] as? JsonObject ?: return null else content
            parse(actual)
        } catch (_: Throwable) {
            null
        }
    }

    private fun invalid(error: InteractiveUiError): InteractiveUiParseResult.Invalid {
        try {
            Timber.w("interactive_ui invalid: %s", error.code.wire)
        } catch (_: Throwable) {
            // Logging must not break text fallback.
        }
        return InteractiveUiParseResult.Invalid(error)
    }
}
