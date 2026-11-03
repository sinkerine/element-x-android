/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.core.protocol

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs

/** Ported from element-web apps/web/src/interactive-ui/protocol.ts at bcb08017 (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial). */
internal const val JS_WHITESPACE_CLASS = "[\\u0009-\\u000d\\u0020\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]"
internal fun jsTrim(value: String): String = value.trim { char ->
    char in '\u0009'..'\u000d' || char == ' ' || char == '\u00a0' || char == '\u1680' ||
        char in '\u2000'..'\u200a' || char == '\u2028' || char == '\u2029' || char == '\u202f' ||
        char == '\u205f' || char == '\u3000' || char == '\ufeff'
}
internal fun codePointLength(value: String): Int = value.codePointCount(0, value.length)
internal fun isSafeInteger(value: Double): Boolean = value.isFinite() && value % 1.0 == 0.0 && abs(value) <= 9_007_199_254_740_991.0
internal fun hasForbiddenControls(value: String): Boolean = value.any { it in '\u0000'..'\u0008' || it in '\u000b'..'\u001f' }
internal fun jsKeyOrder(keys: Collection<String>): List<String> {
    val (indices, rest) = keys.partition { key ->
        key.toLongOrNull()?.let { it in 0..4_294_967_294L && it.toString() == key } == true
    }
    return indices.sortedBy { it.toLong() } + rest
}

/** Shortest round-tripping decimal, choosing the closest decimal and an even significand on ties. */
internal fun jsNumberToString(value: Double): String {
    if (value == 0.0) return "0"
    if (!value.isFinite()) return value.toString()
    val exact = BigDecimal(abs(value))
    var shortest = exact
    for (precision in 1..17) {
        val candidate = exact.round(MathContext(precision, RoundingMode.HALF_EVEN))
        if (candidate.toDouble() == abs(value)) {
            shortest = candidate.stripTrailingZeros()
            break
        }
    }
    val digits = shortest.unscaledValue().toString()
    val exponent = digits.length - shortest.scale() - 1
    val sign = if (value < 0) "-" else ""
    return sign + if (exponent in -6..20) {
        shortest.toPlainString()
    } else {
        digits.take(1) + (if (digits.length > 1) "." + digits.drop(1) else "") + "e" +
            (if (exponent >= 0) "+" else "") + exponent
    }
}
