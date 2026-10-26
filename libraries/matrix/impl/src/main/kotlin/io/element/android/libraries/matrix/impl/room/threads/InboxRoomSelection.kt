/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.room.threads

import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.CurrentUserMembership
import io.element.android.libraries.matrix.api.roomlist.LatestEventValue
import io.element.android.libraries.matrix.api.roomlist.RoomSummary
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.PollContent
import io.element.android.libraries.matrix.api.timeline.item.event.RedactedContent
import io.element.android.libraries.matrix.api.timeline.item.event.StickerContent

/** Prefer known thread rooms, then unread rooms; cap native subscriptions at twenty. */
internal fun selectInboxRooms(summaries: List<RoomSummary>, known: Set<RoomId>, recent: List<RoomId>): List<RoomSummary> =
    summaries.filter { it.info.currentUserMembership == CurrentUserMembership.JOINED && !it.info.isSpace }
        .sortedWith(
            compareByDescending<RoomSummary> { summary ->
                val content = (summary.latestEvent as? LatestEventValue.Remote)?.content
                summary.roomId in known || when (content) {
                    is MessageContent -> content.threadInfo != null
                    is PollContent -> content.threadInfo != null
                    is StickerContent -> content.threadInfo != null
                    is RedactedContent -> content.threadInfo != null
                    else -> false
                }
            }.thenByDescending { it.info.numUnreadMessages > 0 }
                .thenBy { recent.indexOf(it.roomId).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
                .thenByDescending { it.latestEventTimestamp }
        ).take(20)
