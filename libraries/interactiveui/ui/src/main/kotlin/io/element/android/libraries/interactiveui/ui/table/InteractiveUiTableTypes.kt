/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.ui.table

import java.math.BigDecimal
import java.math.MathContext
import java.text.Collator
import java.util.Locale

data class InteractiveUiTableColumn(val key: String, val label: String, val numeric: Boolean, val alignEnd: Boolean)

sealed interface InteractiveUiCell {
    data class Text(val value: String) : InteractiveUiCell
    @Suppress("UseDataClass") // Public contract uses identity for number cells.
    class Number(val value: Double) : InteractiveUiCell
    data object Empty : InteractiveUiCell
}

/** Finite ECMAScript-style decimal display; no locale grouping or loss of significant digits. */
internal fun displayNumber(value: Double): String {
    if (value == 0.0) return "0"
    if (!value.isFinite()) return ""
    val exact = BigDecimal(value)
    val decimal = (1..17).firstNotNullOf { precision ->
        exact.round(MathContext(precision)).takeIf { it.toDouble() == value }
    }.stripTrailingZeros()
    return if (kotlin.math.abs(value) >= 1e-6 && kotlin.math.abs(value) < 1e21) {
        decimal.toPlainString()
    } else {
        decimal.toString().replace("E", "e").replace(Regex("e(-?)0+(\\d+)"), "e$1$2")
    }
}

internal fun InteractiveUiCell.display(): String = when (this) {
    is InteractiveUiCell.Text -> value
    is InteractiveUiCell.Number -> displayNumber(value)
    InteractiveUiCell.Empty -> ""
}

/** Ported from element-web packages/shared-components/src/room/timeline/event-tile/body/InteractiveUiView/table/InteractiveUiTableView.tsx
 * at bcb08017 (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial).
 * Nulls precede values in ascending order; ties retain original order in either direction.
 */
fun sortedRowIndices(rows: List<List<InteractiveUiCell>>, columnIndex: Int, numeric: Boolean, descending: Boolean): List<Int> {
    val collator = Collator.getInstance(Locale.ROOT)
    return rows.indices.sortedWith { a, b ->
        val left = rows[a].getOrElse(columnIndex) { InteractiveUiCell.Empty }
        val right = rows[b].getOrElse(columnIndex) { InteractiveUiCell.Empty }
        val comparison = when {
            left === right -> 0
            left === InteractiveUiCell.Empty -> -1
            right === InteractiveUiCell.Empty -> 1
            numeric && left is InteractiveUiCell.Number && right is InteractiveUiCell.Number -> if (left.value ==
                right.value) {
                    0
                } else {
                    left.value.compareTo(right.value)
                }
            else -> collator.compare(left.display(), right.display())
        }
        (if (descending) -comparison else comparison).takeIf { it != 0 } ?: a.compareTo(b)
    }
}
