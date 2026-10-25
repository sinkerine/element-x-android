/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.threads

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.timeline.aTimelineItemEvent
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.virtual.TimelineItemReadMarkerModel
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UniqueId
import org.junit.Test

class TriageUnreadBoundaryTest {
    @Test
    fun `fallback removes SDK marker without inventing an unread boundary`() {
        val event = aTimelineItemEvent(eventId = EventId("\$reply"))
        val old = TimelineItem.Virtual(UniqueId("sdk-read-marker"), TimelineItemReadMarkerModel)
        assertThat(withTriageUnreadBoundary(listOf(event, old), null)).containsExactly(event)
    }

    @Test
    fun `captured boundary remains above first unread even when SDK marker moves`() {
        val latest = aTimelineItemEvent(eventId = EventId("\$latest"))
        val unread = aTimelineItemEvent(eventId = EventId("\$unread"))
        val read = aTimelineItemEvent(eventId = EventId("\$read"))
        val sdk = TimelineItem.Virtual(UniqueId("sdk-read-marker"), TimelineItemReadMarkerModel)
        val first = withTriageUnreadBoundary(listOf(latest, sdk, unread, read), unread.eventId)
        val updated = withTriageUnreadBoundary(listOf(sdk, latest, unread, read), unread.eventId)
        assertThat(updated).isEqualTo(first)
        assertThat((updated[2] as TimelineItem.Virtual).id).isEqualTo(UniqueId("triage-new-divider"))
        assertThat(updated[1]).isEqualTo(unread)
    }
}
