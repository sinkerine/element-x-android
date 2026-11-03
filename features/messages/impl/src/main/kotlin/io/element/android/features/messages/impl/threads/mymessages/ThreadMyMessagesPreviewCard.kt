/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.threads.mymessages

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.messages.impl.R
import io.element.android.libraries.designsystem.components.avatar.Avatar
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarType
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Surface
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.ui.strings.CommonStrings

@Composable
internal fun ThreadMyMessagesPreviewCard(item: ThreadMyMessageItem, avatar: AvatarData, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(horizontal = 12.dp).fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = ElementTheme.colors.bgCanvasDefault,
        border = BorderStroke(1.5.dp, ElementTheme.colors.borderAccentSubtle),
        shadowElevation = 4.dp,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Avatar(avatar, AvatarType.User)
                Text("${stringResource(CommonStrings.common_you)} · ${item.fullTime}", style = ElementTheme.typography.fontBodySmRegular)
            }
            if (item.isEdited) Text(stringResource(CommonStrings.common_edited_suffix), style = ElementTheme.typography.fontBodySmRegular)
            if (item.isRoot) {
                Surface(color = ElementTheme.colors.bgSubtleSecondary, shape = RoundedCornerShape(4.dp)) {
                Text(stringResource(R.string.screen_thread_my_messages_thread_root), Modifier.padding(4.dp), style = ElementTheme.typography.fontBodySmRegular)
            }
            }
            Text(item.body, maxLines = 8, overflow = TextOverflow.Ellipsis)
            item.context?.let {
                Text(
                    stringResource(R.string.screen_thread_my_messages_context, it.senderName, it.snippet),
                    style = ElementTheme.typography.fontBodySmRegular,
                    color = ElementTheme.colors.textSecondary
                )
            }
        }
    }
}

@PreviewsDayNight
@Composable
internal fun ThreadMyMessagesPreviewCardPreview(@PreviewParameter(ThreadMyMessagesStateProvider::class) state: ThreadMyMessagesState) = ElementPreview {
    state.items.firstOrNull()?.let { ThreadMyMessagesPreviewCard(it, state.ownAvatar) }
}
