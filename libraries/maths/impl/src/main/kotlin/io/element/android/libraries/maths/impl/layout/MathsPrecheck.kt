/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.maths.impl.layout

internal object MathsPrecheck {
    const val MAX_TEX_LENGTH = 2_000
    val FORBIDDEN_COMMANDS = setOf("def", "gdef", "edef", "xdef", "let", "newcommand", "renewcommand", "providecommand", "futurelet")
    private val commands = Regex("""\\([a-zA-Z]+)""")

    fun isAllowed(tex: String): Boolean = tex.isNotBlank() && tex.length <= MAX_TEX_LENGTH &&
        commands.findAll(tex).none { it.groupValues[1] in FORBIDDEN_COMMANDS }
}
