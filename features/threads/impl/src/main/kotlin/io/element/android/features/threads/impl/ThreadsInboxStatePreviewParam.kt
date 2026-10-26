/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.threads.impl

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.room.RoomNotificationMode
import io.element.android.libraries.matrix.api.room.threads.InboxThread
import io.element.android.libraries.matrix.api.room.threads.InboxThreadKey
import io.element.android.libraries.matrix.api.room.threads.ThreadListItemEvent
import io.element.android.libraries.matrix.api.timeline.item.event.ProfileDetails
import kotlinx.collections.immutable.persistentListOf

internal fun previewThread() = InboxThread(
    key = InboxThreadKey(RoomId("!test:example.org"), ThreadId("\$thread")),
    roomName = "Android team",
    roomAvatarUrl = null,
    rootEvent = ThreadListItemEvent(
        EventId("\$thread"),
        UserId("@test:example.org"),
        ProfileDetails.Unavailable,
        true,
        io.element.android.libraries.matrix.api.timeline.item.event.MessageContent(
            "Where should we meet?",
            null,
            false,
            null,
            io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType("Where should we meet?", null),
        ),
        0,
    ),
    latestEvent = null,
    numberOfReplies = 2,
    numUnread = 1,
    numUnreadMentions = 0,
    isParticipating = true,
    roomNotificationMode = RoomNotificationMode.ALL_MESSAGES,
    readReceiptEventId = null,
)

internal class ThreadsInboxStatePreviewParam : PreviewParameterProvider<ThreadsInboxState> {
    override val values: Sequence<ThreadsInboxState>
        get() {
            val thread = previewThread()
            val state = ThreadsInboxState(
                rows = AsyncData.Success(persistentListOf(thread)),
                tab = ThreadsInboxTab.Mine,
                filter = ThreadsInboxFilter.Unread,
                visited = emptySet(),
                pending = emptySet(),
                mineUnreadCount = 1,
                otherUnreadCount = 0,
                latestUnread = thread,
                failed = false,
                summaries = mapOf(thread.key to ("Where should we meet?" to "Lena: Could you mock this up?")),
                eventSink = {},
            )
            return sequenceOf(
                state,
                state.copy(rows = AsyncData.Loading()),
                state.copy(rows = AsyncData.Loading(persistentListOf(thread))),
                state.copy(rows = AsyncData.Loading(persistentListOf()), latestUnread = null),
                state.copy(rows = AsyncData.Success(persistentListOf()), latestUnread = null),
                state.copy(rows = AsyncData.Success(persistentListOf()), filter = ThreadsInboxFilter.All, latestUnread = null),
                state.copy(pending = setOf(thread.key)),
                state.copy(failed = true),
                state.copy(rows = AsyncData.Failure(IllegalStateException())),
                state.copy(rows = AsyncData.Success(persistentListOf(thread.copy(numUnreadMentions = 2)))),
                state.copy(rows = AsyncData.Success(persistentListOf(thread.copy(numUnread = 0))), visited = setOf(thread.key)),
                state.copy(rows = AsyncData.Failure(IllegalStateException(), persistentListOf(thread))),
            )
        }
}

@PreviewsDayNight
@Composable
internal fun ThreadsInboxPreview(@PreviewParameter(ThreadsInboxStatePreviewParam::class) state: ThreadsInboxState) = ElementPreview {
    ThreadsInboxView(state, {}, {})
}
