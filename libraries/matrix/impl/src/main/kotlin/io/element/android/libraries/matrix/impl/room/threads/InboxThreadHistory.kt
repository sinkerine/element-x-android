/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.room.threads

import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.timeline.item.event.EventTimelineItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class InboxThreadHistory(
    val unreadCount: Long,
    val mentionCount: Long,
    val participating: Boolean,
    val firstUnread: EventId?,
    val effectiveReceipt: EventId? = null,
    /** False when the scan was capped: counts are lower bounds and [firstUnread] is not the boundary. */
    val complete: Boolean = true,
)

/**
 * Classification that needs no reply history: the latest reply is covered by a receipt or is the user's own,
 * or there are no replies. Returns null when replies newer than the read boundary must be scanned.
 */
internal fun localThreadHistory(latest: EventId, latestIsOwn: Boolean, hasReplies: Boolean, receipts: Set<EventId>): InboxThreadHistory? = when {
    !hasReplies -> InboxThreadHistory(0, 0, latestIsOwn, null, receipts.firstOrNull())
    latest in receipts -> InboxThreadHistory(0, 0, false, null, latest)
    latestIsOwn -> InboxThreadHistory(0, 0, true, null, latest)
    else -> null
}

/** History order, rather than receipt timestamps or lexicographic event IDs, determines unread. */
internal fun aggregateThreadHistory(
    events: List<EventTimelineItem>,
    rootId: String,
    receiptId: EventId?,
    ownUserId: UserId,
    receiptIds: Set<EventId> = setOfNotNull(receiptId),
): InboxThreadHistory {
    val ordered = events.distinctBy { it.eventId }
    check(receiptIds.all { id -> ordered.any { it.eventId == id } }) { "Thread receipt is outside loaded history" }
    val receiptIndex = ordered.indexOfLast { it.eventId in receiptIds }
    val unread = ordered.drop(receiptIndex + 1).filter { it.eventId?.value != rootId && !it.isOwn }
    val mentions = unread.count { event ->
        val original = event.timelineItemDebugInfoProvider().originalJson ?: return@count false
        val json = Json.parseToJsonElement(original).jsonObject
        val eventJson = json["event"]?.jsonObject ?: json
        val content = eventJson["content"]?.jsonObject ?: return@count false
        val explicit = content["m.mentions"]?.jsonObject ?: return@count false
        explicit["room"]?.jsonPrimitive?.content == "true" ||
            explicit["user_ids"]?.jsonArray?.any { it.jsonPrimitive.content == ownUserId.value } == true
    }
    return InboxThreadHistory(
        unreadCount = unread.size.toLong(),
        mentionCount = mentions.toLong(),
        participating = ordered.any { it.isOwn },
        firstUnread = unread.firstOrNull()?.eventId,
        effectiveReceipt = ordered.getOrNull(receiptIndex)?.eventId,
    )
}
