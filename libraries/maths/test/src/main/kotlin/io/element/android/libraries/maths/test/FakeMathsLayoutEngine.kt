/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.maths.test

import io.element.android.libraries.maths.api.MathsLayout
import io.element.android.libraries.maths.api.MathsLayoutEngine

class FakeMathsLayoutEngine(
    private val layoutResult: (tex: String, displayMode: Boolean) -> MathsLayout? = { _, _ -> FakeMathsLayout() },
) : MathsLayoutEngine {
    private val recordedCalls = mutableListOf<Pair<String, Boolean>>()
    val calls: List<Pair<String, Boolean>> get() = recordedCalls.toList()

    override fun layout(tex: String, displayMode: Boolean): MathsLayout? {
        recordedCalls += tex to displayMode
        return layoutResult(tex, displayMode)
    }
}
