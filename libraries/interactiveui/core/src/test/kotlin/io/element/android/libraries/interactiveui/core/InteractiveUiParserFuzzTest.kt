/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import kotlin.random.Random

/**
 * Ported from element-web apps/web/src/interactive-ui/protocol.test.ts at bcb08017
 * (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial).
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], manifest = org.robolectric.annotation.Config.NONE)
class InteractiveUiParserFuzzTest {
    @Test fun `2000 seeded mutations never throw`() {
        val random = Random(137)
        val names = fixtureNames("valid")
        val replacements = listOf(
            JsonNull,
            JsonArray(emptyList()),
            JsonObject(emptyMap()),
            JsonPrimitive(false),
            JsonPrimitive(1),
            JsonPrimitive("bad\u0000"),
            JsonPrimitive(Double.POSITIVE_INFINITY),
            JsonPrimitive("x".repeat(61_000))
        )
        fun mutate(value: JsonElement, depth: Int = 0): JsonElement {
            if (depth > 4 || random.nextInt(4) == 0) return replacements.random(random)
            return when (value) {
                is JsonObject -> {
                    val key = value.keys.randomOrNull(random) ?: return value
                    value.with(key, mutate(value.getValue(key), depth + 1))
                }
                is JsonArray -> {
                    if (value.isEmpty()) return value
                    val index = random.nextInt(value.size)
                    JsonArray(value.mapIndexed { i, child -> if (i == index) mutate(child, depth + 1) else child })
                }
                else -> replacements.random(random)
            }
        }
        repeat(2000) { parse(mutate(Json.parseToJsonElement(resource("fixtures/valid/${names.random(random)}")))) }
    }
}
