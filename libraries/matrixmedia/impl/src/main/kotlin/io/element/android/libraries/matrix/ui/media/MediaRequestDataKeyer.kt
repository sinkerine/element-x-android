/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.ui.media

import coil3.key.Keyer
import coil3.request.Options
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.matrix.api.media.MediaSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.ByteString.Companion.encodeUtf8

internal class AvatarDataKeyer : Keyer<AvatarData> {
    override fun key(data: AvatarData, options: Options): String? {
        return data.toMediaRequestData().toKey()
    }
}

internal class MediaRequestDataKeyer : Keyer<MediaRequestData> {
    override fun key(data: MediaRequestData, options: Options): String? {
        return data.toKey()
    }
}

private fun MediaRequestData.toKey(): String? {
    return source?.let {
        if (it.isEncrypted) {
            // Hash the complete descriptor, including key material, without exposing it in a cache key.
            val descriptor = canonicalJson(parseMediaSourceJson(requireNotNull(it.json)))
            val identity = JsonArray(listOf(JsonPrimitive(it.safeUrl), descriptor)).toString().encodeUtf8().sha256().hex()
            "matrix-media-v2:encrypted:${identity}_$kind"
        } else {
            "matrix-media-v2:plain:${it.safeUrl}_$kind"
        }
    }
}

// The SDK serializes plain sources as {"url": ...} and encrypted sources as {"file": ...}.
internal val MediaSource.isEncrypted: Boolean
    get() = json?.let { (parseMediaSourceJson(it) as? JsonObject)?.containsKey("file") } == true

private fun parseMediaSourceJson(json: String): JsonElement = try {
    Json.parseToJsonElement(json)
} catch (_: IllegalArgumentException) {
    // JSON parser exceptions can contain the input, including encryption keys.
    throw IllegalArgumentException("Invalid media source JSON")
}

private fun canonicalJson(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.toSortedMap().mapValues { canonicalJson(it.value) })
    is JsonArray -> JsonArray(element.map(::canonicalJson))
    else -> element
}
