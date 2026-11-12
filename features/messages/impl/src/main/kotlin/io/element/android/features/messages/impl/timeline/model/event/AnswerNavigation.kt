/*
 * Copyright 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.model.event

import kotlinx.collections.immutable.ImmutableList

/** Render-only sections; original message content remains the source for copying, editing and replies. */
data class AnswerNavigation(
    val introduction: CharSequence,
    val sections: ImmutableList<AnswerSection>,
    val sources: ImmutableList<AnswerSource>,
)

data class AnswerSection(
    val title: String,
    val level: Int,
    val heading: CharSequence,
    val body: CharSequence,
)

data class AnswerSource(val label: String, val url: String, val host: String)
