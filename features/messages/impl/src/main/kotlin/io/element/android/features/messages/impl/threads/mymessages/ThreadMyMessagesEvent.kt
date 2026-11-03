/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.threads.mymessages

import io.element.android.libraries.matrix.api.core.EventId
import kotlinx.collections.immutable.ImmutableList

sealed interface ThreadMyMessagesEvent {
    data object Open : ThreadMyMessagesEvent
    data object Close : ThreadMyMessagesEvent
    data object Retry : ThreadMyMessagesEvent
    data class JumpTo(val eventId: EventId) : ThreadMyMessagesEvent
    data object JumpConsumed : ThreadMyMessagesEvent
    data object JumpToPrevious : ThreadMyMessagesEvent
    data class Peek(val eventId: EventId) : ThreadMyMessagesEvent
    data class Preview(val eventId: EventId) : ThreadMyMessagesEvent
    data object EndPeek : ThreadMyMessagesEvent
    data class VisibleEventsChanged(val newestFirst: ImmutableList<EventId>) : ThreadMyMessagesEvent
    data object AnnouncementConsumed : ThreadMyMessagesEvent
}
