/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.maths.impl.detection

/** Bounds all HTML searches together, including failed repeated-prefix comparisons. */
internal class MathsSearchBudget {
    private var remaining = 1_000_000
    fun spend() {
        check(remaining-- > 0) { "Maths search budget exceeded" }
    }
}
