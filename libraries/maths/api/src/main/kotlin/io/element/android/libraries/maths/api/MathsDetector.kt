/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.maths.api

import org.jsoup.nodes.Document

/** Finds LaTeX delimiters in message text. */
interface MathsDetector {
    /**
     * Finds delimiters in raw [body], inserting span/div maths elements with code fallbacks into a clone of [document],
     * or a new document built from [body] with br elements for newlines. Returns null when nothing was inserted.
     * Never mutates [document]. Never throws.
     */
    fun detect(body: String, document: Document?): Document?
}
