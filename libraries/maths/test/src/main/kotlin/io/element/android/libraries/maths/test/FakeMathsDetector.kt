/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.maths.test

import io.element.android.libraries.maths.api.MathsDetector
import org.jsoup.nodes.Document

class FakeMathsDetector(
    private val detectResult: (body: String, document: Document?) -> Document? = { _, _ -> null },
) : MathsDetector {
    private val recordedCalls = mutableListOf<String>()
    val calls: List<String> get() = recordedCalls.toList()

    override fun detect(body: String, document: Document?): Document? {
        recordedCalls += body
        return detectResult(body, document)
    }
}
