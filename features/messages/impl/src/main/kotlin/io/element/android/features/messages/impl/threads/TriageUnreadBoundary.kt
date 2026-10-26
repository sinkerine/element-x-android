/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.threads

import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.virtual.TimelineItemReadMarkerModel
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UniqueId
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** UI items are reversed: index + 1 renders immediately above the captured first unread reply. */
internal fun withTriageUnreadBoundary(items: List<TimelineItem>, firstUnread: EventId?): ImmutableList<TimelineItem> {
    val result = items.filterNot { it is TimelineItem.Virtual && it.model is TimelineItemReadMarkerModel }.toMutableList()
    val index = if (firstUnread == null) -1 else result.indexOfFirst { it is TimelineItem.Event && it.eventId == firstUnread }
    if (index >= 0) result.add(index + 1, TimelineItem.Virtual(UniqueId("triage-new-divider"), TimelineItemReadMarkerModel))
    return result.toImmutableList()
}
