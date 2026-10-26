/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.threads.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ListItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.atomic.atoms.CounterAtom
import io.element.android.libraries.designsystem.components.avatar.Avatar
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.components.avatar.AvatarType
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrix.api.room.threads.InboxThread
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.libraries.ui.strings.R

@Composable
internal fun ThreadsInboxRow(
    thread: InboxThread,
    rootPreview: String,
    replyPreview: String,
    visited: Boolean,
    onOpen: () -> Unit,
    onLongClick: () -> Unit,
    onMarkRead: () -> Unit,
    onTouch: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    LaunchedEffect(interactions, thread.key) {
        try {
            interactions.interactions.collect { action ->
                when (action) {
                    is PressInteraction.Press -> onTouch(true)
                    is PressInteraction.Release, is PressInteraction.Cancel -> onTouch(false)
                }
            }
        } finally {
            onTouch(false)
        }
    }
    val unread = thread.isUnread
    val label = stringResource(CommonStrings.screen_threads_inbox_mark_read)
    val time = inboxTimeLabel(thread.timestamp)
    val unreadCount = thread.numUnread.coerceAtLeast(if (thread.isMarkedUnread) 1 else 0)
    val description = stringResource(
        CommonStrings.a11y_threads_inbox_row,
        thread.roomName.orEmpty(),
        rootPreview,
        replyPreview,
        time,
        pluralStringResource(R.plurals.a11y_threads_inbox_unread, unreadCount.toInt(), unreadCount),
        pluralStringResource(R.plurals.a11y_threads_inbox_mentions, thread.numUnreadMentions.toInt(), thread.numUnreadMentions),
    )
    ListItem(
        leadingContent = { Avatar(AvatarData(thread.key.roomId.value, thread.roomName, thread.roomAvatarUrl, AvatarSize.ThreadsListItem), AvatarType.Room()) },
        headlineContent = { Text(thread.roomName ?: thread.key.roomId.value, style = ElementTheme.typography.fontBodyLgMedium, maxLines = 1) },
        supportingContent = {
            Column {
                Row {
                    Icon(CompoundIcons.Threads(), null, modifier = Modifier.size(16.dp))
                    Text(rootPreview, style = ElementTheme.typography.fontBodyMdRegular, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    replyPreview,
                    style = ElementTheme.typography.fontBodyMdRegular,
                    color = ElementTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        trailingContent = {
            Column {
                Text(
                    time,
                    color = if (unread) ElementTheme.colors.textSuccessPrimary else ElementTheme.colors.textSecondary,
                    style = ElementTheme.typography.fontBodySmMedium
                )
                if (unread) {
                    if (thread.numUnreadMentions > 0) {
                        Text("@ ${thread.numUnreadMentions}", color = ElementTheme.colors.textCriticalPrimary)
                    } else {
                        CounterAtom(unreadCount.toInt())
                    }
                }
            }
        },
        // Opaque canvas below the dimmed content, so the swipe action never shows through read rows.
        modifier = modifier.background(ElementTheme.colors.bgCanvasDefault).alpha(if (unread) 1f else 0.55f)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                customActions = listOf(CustomAccessibilityAction(label) {
                    onMarkRead()
                    true
                })
            }
            .combinedClickable(interactionSource = interactions, indication = null, onClick = onOpen, onLongClick = onLongClick),
    )
}
