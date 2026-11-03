/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.threads.mymessages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.impl.R
import io.element.android.libraries.designsystem.components.avatar.Avatar
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarType
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Surface

@Composable
internal fun ThreadMyMessagesButton(avatar: AvatarData, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.a11y_thread_my_messages_open)
    Box(
        modifier.padding(bottom = 12.dp).size(48.dp).semantics { contentDescription = label }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(14.dp), color = ElementTheme.colors.bgCanvasDefault, shadowElevation = 4.dp) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Avatar(avatarData = avatar, avatarType = AvatarType.User, contentDescription = null)
                Icon(
                    imageVector = CompoundIcons.ListBulleted(),
                    contentDescription = null,
                    tint = ElementTheme.colors.iconOnSolidPrimary,
                    modifier = Modifier.align(Alignment.BottomEnd).background(ElementTheme.colors.iconAccentPrimary, CircleShape)
                        .size(18.dp)
                        .padding(3.dp),
                )
            }
        }
    }
}
