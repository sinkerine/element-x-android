/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.maths.api

/** Lays out TeX maths for drawing. Implementations are thread-safe and cache results. */
interface MathsLayoutEngine {
    /**
     * Lays out [tex] without delimiters in display (block) or inline (text) style.
     * Blocking and CPU-bound: call off the main thread. Returns null on invalid input, limits or unavailable engine.
     * Never throws.
     */
    fun layout(tex: String, displayMode: Boolean): MathsLayout?
}
