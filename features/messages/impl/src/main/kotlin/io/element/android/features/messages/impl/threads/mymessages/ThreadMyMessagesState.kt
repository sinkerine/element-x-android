/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.threads.mymessages

import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.matrix.api.core.EventId
import kotlinx.collections.immutable.ImmutableList

data class ThreadMyMessagesState(
    val isEntryVisible: Boolean,
    val ownAvatar: AvatarData,
    val isOpen: Boolean,
    val items: ImmutableList<ThreadMyMessageItem>,
    val isComplete: Boolean,
    val isLimitReached: Boolean,
    val loadState: ThreadMyMessagesLoadState,
    val currentEventId: EventId?,
    val pendingJump: EventId?,
    val announcement: String?,
    val previousEventId: EventId?,
    val isReadingPositionReady: Boolean,
    val peek: ThreadMyMessagePeek?,
    val eventSink: (ThreadMyMessagesEvent) -> Unit,
)

data class ThreadMyMessageItem(
    val eventId: EventId,
    val time: String,
    val fullTime: String,
    val snippet: String,
    val body: String,
    val isRoot: Boolean,
    val isEdited: Boolean,
    val isOnScreen: Boolean = false,
    val context: ThreadMyMessageContext? = null,
)

data class ThreadMyMessageContext(val senderName: String, val snippet: String)

sealed interface ThreadMyMessagePeek {
    val eventId: EventId
    data class Card(override val eventId: EventId) : ThreadMyMessagePeek
    data class Outline(override val eventId: EventId) : ThreadMyMessagePeek
}

enum class ThreadMyMessagesLoadState { Idle, Loading, Failed }
