/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.threads.impl

import dev.zacsweers.metro.Inject
import io.element.android.libraries.eventformatter.api.RoomLatestEventFormatter
import io.element.android.libraries.matrix.api.room.threads.InboxThread
import io.element.android.libraries.matrix.api.room.threads.ThreadListItemEvent
import io.element.android.libraries.matrix.api.roomlist.LatestEventValue

/** Reuse the session preview formatter so media, polls, redactions and formatted text match Home. */
@Inject
class InboxThreadSummaryFormatter(private val formatter: RoomLatestEventFormatter) {
    fun format(thread: InboxThread): Pair<String, String> =
        format(thread.rootEvent, includeSender = false) to (thread.latestEvent?.let { format(it, includeSender = true) }.orEmpty())

    private fun format(event: ThreadListItemEvent, includeSender: Boolean): String {
        val content = event.content ?: return ""
        return formatter.format(
            LatestEventValue.Remote(event.timestamp, content, event.senderId, event.senderProfile, event.isOwn),
            isDmRoom = !includeSender,
        )?.toString().orEmpty()
    }
}
