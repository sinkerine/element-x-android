/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.threads.mymessages

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.matrix.api.core.EventId
import kotlinx.collections.immutable.persistentListOf

internal class ThreadMyMessagesStateProvider : PreviewParameterProvider<ThreadMyMessagesState> {
    override val values: Sequence<ThreadMyMessagesState> = sequenceOf(
        aThreadMyMessagesState(),
        aThreadMyMessagesState().copy(loadState = ThreadMyMessagesLoadState.Failed),
        aThreadMyMessagesState().copy(isComplete = true, loadState = ThreadMyMessagesLoadState.Idle),
        aThreadMyMessagesState().copy(items = persistentListOf()),
        aThreadMyMessagesState().copy(items = persistentListOf(), isComplete = true, loadState = ThreadMyMessagesLoadState.Idle),
        aThreadMyMessagesState().copy(currentEventId = EventId("$" + "root"), previousEventId = EventId("$" + "root")),
        aThreadMyMessagesState().copy(peek = ThreadMyMessagePeek.Card(EventId("$" + "reply"))),
        aThreadMyMessagesState().copy(peek = ThreadMyMessagePeek.Outline(EventId("$" + "root"))),
        aThreadMyMessagesState().copy(isLimitReached = true, loadState = ThreadMyMessagesLoadState.Idle),
    )
}

internal fun aThreadMyMessagesState() = ThreadMyMessagesState(
    isEntryVisible = true,
    ownAvatar = AvatarData("@alex:example.org", "Alex", size = AvatarSize.ThreadMyMessagesButton),
    isOpen = true,
    items = persistentListOf(
        ThreadMyMessageItem(
            EventId("$" + "reply"),
            "10:42",
            "Today at 10:42",
            "I can help with that.",
            "I can help with that.",
            false,
            true,
            context = ThreadMyMessageContext("Sam", "Could someone help?")
        ),
        ThreadMyMessageItem(EventId("$" + "root"), "09:15", "Today at 09:15", "Plans for this week", "Plans for this week", true, false, isOnScreen = true),
    ),
    isComplete = false,
    isLimitReached = false,
    loadState = ThreadMyMessagesLoadState.Loading,
    currentEventId = null,
    pendingJump = null,
    announcement = null,
    isReadingPositionReady = true,
    eventSink = {},
)
