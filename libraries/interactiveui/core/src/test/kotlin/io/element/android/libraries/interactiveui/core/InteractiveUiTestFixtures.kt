/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.core

import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiConstants
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiParseResult
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Ported from element-web apps/web/src/interactive-ui/protocol.test.ts at bcb08017
 * (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial).
 */
internal fun resource(path: String): String = checkNotNull(InteractiveUiConformanceTest::class.java.getResourceAsStream("/interactive-ui/$path"))
    .bufferedReader()
    .use { it.readText() }
internal fun fixture(name: String): JsonObject = Json.parseToJsonElement(resource("fixtures/valid/$name.json")).jsonObject
internal fun parse(raw: JsonElement): InteractiveUiParseResult = InteractiveUiParser.parse(JsonObject(mapOf(InteractiveUiConstants.PAYLOAD_KEY to raw)))!!
internal fun parsed(name: String) = (parse(fixture(name)) as InteractiveUiParseResult.Valid).payload
internal fun JsonObject.with(key: String, value: JsonElement): JsonObject = JsonObject(this + (key to value))
internal fun fixtureNames(directory: String): List<String> = resource("manifest-paths.txt").lineSequence()
    .filter { it.startsWith("fixtures/$directory/") && it.endsWith(".json") && !it.endsWith(".expected.json") }
    .map { it.substringAfterLast('/') }
    .toList()
