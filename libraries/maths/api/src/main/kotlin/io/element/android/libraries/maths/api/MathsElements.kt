/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.maths.api

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Matrix spec attribute carrying TeX source on span (inline) and div (display) elements. */
const val MATHS_ATTRIBUTE = "data-mx-maths"

/** Maximum maths elements rendered per message; later ones fall back. */
const val MAX_MATHS_ELEMENTS_PER_MESSAGE = 50

fun Element.isMathsElement(): Boolean = normalName() in setOf("span", "div") && hasAttr(MATHS_ATTRIBUTE)
fun Element.isDisplayMaths(): Boolean = isMathsElement() && normalName() == "div"
fun Element.mathsTex(): String = attr(MATHS_ATTRIBUTE)

/** Unwraps an inline element; preserves a display element's block structure as a paragraph. */
fun Element.replaceMathsWithFallback() {
    if (isDisplayMaths()) {
        tagName("p").removeAttr(MATHS_ATTRIBUTE)
    } else {
        unwrap()
    }
}

/** Creates a maths element with a literal code fallback, using [document]'s element factory. */
fun createMathsElement(document: Document, tex: String, displayMode: Boolean): Element =
    document.createElement(if (displayMode) "div" else "span").attr(MATHS_ATTRIBUTE, tex).apply {
        appendElement("code").text(tex)
    }
