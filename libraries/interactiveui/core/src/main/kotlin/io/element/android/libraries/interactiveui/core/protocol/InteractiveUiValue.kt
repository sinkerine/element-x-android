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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Ported from element-web apps/web/src/interactive-ui/protocol.ts at bcb08017 (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial). */
sealed interface InteractiveUiValue {
    data class Text(val value: String) : InteractiveUiValue

    // A data class would give signed zero JVM equality instead of JS strict equality.
    @Suppress("UseDataClass")
    class Number(val value: Double) : InteractiveUiValue {
        override fun equals(other: Any?): Boolean = other is Number && value == other.value
        override fun hashCode(): Int = (value + 0.0).hashCode()
        override fun toString(): String = jsNumberToString(value)
    }
    data class Bool(val value: Boolean) : InteractiveUiValue
    data class TextList(val values: List<String>) : InteractiveUiValue
    data object Null : InteractiveUiValue
}

fun InteractiveUiValue.toJsonElement(): JsonElement = when (this) {
    is InteractiveUiValue.Text -> JsonPrimitive(value)
    is InteractiveUiValue.Number -> if (value.isFinite()) JsonPrimitive(jsNumberToString(value).toDouble()) else JsonNull
    is InteractiveUiValue.Bool -> JsonPrimitive(value)
    is InteractiveUiValue.TextList -> JsonArray(values.map(::JsonPrimitive))
    InteractiveUiValue.Null -> JsonNull
}

fun JsonElement.toInteractiveUiValueOrNull(): InteractiveUiValue? = when {
    this === JsonNull -> InteractiveUiValue.Null
    this is JsonPrimitive && isString -> InteractiveUiValue.Text(content)
    this is JsonPrimitive && booleanOrNull != null -> InteractiveUiValue.Bool(boolean)
    this is JsonPrimitive -> doubleOrNull?.takeIf { it.isFinite() }?.let(InteractiveUiValue::Number)
    this is JsonArray && all { it is JsonPrimitive && it.isString } -> InteractiveUiValue.TextList(map { it.jsonPrimitive.content })
    else -> null
}
