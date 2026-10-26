/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import io.element.android.compound.tokens.generated.CompoundIcons

enum class HomeNavigationBarItem(
    @StringRes
    val labelRes: Int,
) {
    Chats(
        labelRes = R.string.screen_home_tab_chats
    ),
    Spaces(
        labelRes = R.string.screen_home_tab_spaces
    ),
    Threads(labelRes = io.element.android.libraries.ui.strings.CommonStrings.screen_home_tab_threads);

    @Composable
    fun icon(
        isSelected: Boolean,
    ) = when (this) {
        Threads -> if (isSelected) CompoundIcons.ThreadsSolid() else CompoundIcons.Threads()
        Chats -> if (isSelected) CompoundIcons.ChatSolid() else CompoundIcons.Chat()
        Spaces -> if (isSelected) CompoundIcons.SpaceSolid() else CompoundIcons.Space()
    }

    companion object {
        fun from(index: Int): HomeNavigationBarItem {
            return entries.getOrElse(index) { Chats }
        }
    }
}
