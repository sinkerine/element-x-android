/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.ui.media

import coil3.request.Options
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.media.MediaSource
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Test

class MediaRequestDataKeyerTest {
    private val keyer = MediaRequestDataKeyer()
    private val options = Options(mockk())

    @Test
    fun `every descriptor field contributes to a versioned key without exposing secrets`() {
        val source = encryptedMediaSource()
        val key = key(source)
        assertThat(key).startsWith("matrix-media-v2:encrypted:")
        assertThat(key).doesNotContain("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        assertThat(key).doesNotContain("mxc://server/image")
        for ((original, replacement) in listOf(
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" to "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
            "AAAAAAAAAAAAAAAAAAAAAA" to "BBBBBBBBBBBBBBBBBBBBBB",
            "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC" to "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD",
            "v2" to "v3",
            "A256CTR" to "A128CTR",
            "oct" to "other",
            "true" to "false",
            "encrypt" to "other",
            "mxc://server/image" to "mxc://server/other",
        )) {
            assertThat(key(source.copy(json = source.json!!.replace(original, replacement)))).isNotEqualTo(key)
        }
        assertThat(key(MediaSource("mxc://server/other", source.json))).isNotEqualTo(key)
    }

    @Test
    fun `malformed JSON cannot expose descriptor secrets through parser exceptions`() {
        val secret = "sensitive-key-material"
        val source = MediaSource("mxc://server/image", """{"file":{"key":"$secret"} BROKEN}""")
        val error = try {
            key(source)
            null
        } catch (error: IllegalArgumentException) {
            error
        }
        assertThat(error).hasMessageThat().isEqualTo("Invalid media source JSON")
        assertThat(error?.cause).isNull()
        assertThat(error.toString()).doesNotContain(secret)
    }

    @Test
    fun `object order and whitespace do not change descriptor identity`() {
        val source = encryptedMediaSource()
        val reordered = reverseObjects(Json.parseToJsonElement(source.json!!)).toString()
        assertThat(key(source.copy(json = "\n $reordered \n"))).isEqualTo(key(source))
    }

    @Test
    fun `plain encrypted and legacy cache keys are distinct`() {
        val encrypted = encryptedMediaSource()
        val plain = MediaSource(encrypted.safeUrl)
        assertThat(key(plain)).isNotEqualTo(key(encrypted))
        assertThat(key(plain)).isNotEqualTo("${plain.safeUrl}_Content")
        assertThat(key(encrypted)).isNotEqualTo("${encrypted.safeUrl}_Content")
        assertThat(key(plain.copy(json = """{"url":"${plain.safeUrl}"}"""))).isEqualTo(key(plain))
    }

    @Test
    fun `request kind and thumbnail dimensions remain part of identity`() {
        val source = encryptedMediaSource()
        val keys = listOf(
            MediaRequestData.Kind.Content,
            MediaRequestData.Kind.Thumbnail(32, 32),
            MediaRequestData.Kind.Thumbnail(64, 32),
            MediaRequestData.Kind.Thumbnail(32, 64),
            MediaRequestData.Kind.File("image.png", "image/png"),
        ).map { keyer.key(MediaRequestData(source, it), options) }
        assertThat(keys.toSet()).hasSize(keys.size)
        assertThat(keyer.key(MediaRequestData(null, MediaRequestData.Kind.Content), options)).isNull()
    }

    private fun key(source: MediaSource) = keyer.key(MediaRequestData(source, MediaRequestData.Kind.Content), options)

    private fun reverseObjects(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.reversed().associate { it.key to reverseObjects(it.value) })
        is JsonArray -> JsonArray(element.map(::reverseObjects))
        else -> element
    }
}

internal fun encryptedMediaSource(): MediaSource = MediaSource(
    "mxc://server/image",
    """{"file":{"url":"mxc://server/image",
        "key":{"kty":"oct","key_ops":["encrypt","decrypt"],"alg":"A256CTR","k":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","ext":true},
        "iv":"AAAAAAAAAAAAAAAAAAAAAA","hashes":{"sha256":"CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"},"v":"v2"}}""".trimIndent(),
)
