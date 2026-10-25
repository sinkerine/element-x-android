/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.room.threads

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.timeline.item.TimelineItemDebugInfo
import io.element.android.libraries.matrix.test.timeline.anEventTimelineItem
import org.junit.Test

class InboxThreadHistoryTest {
    private val own = UserId("@tester:example.org")
    private val root = anEventTimelineItem(eventId = EventId("\$root"), isOwn = true, timestamp = 1)
    private val first = anEventTimelineItem(eventId = EventId("\$reply1"), timestamp = 2)
    private val mention = anEventTimelineItem(
        eventId = EventId("\$reply2"),
        timestamp = 3,
        debugInfoProvider = { TimelineItemDebugInfo("", """{"content":{"m.mentions":{"user_ids":["@tester:example.org"]}}}""", null) },
    )

    @Test
    fun `no receipt counts replies from root and recognizes earlier participation and mention`() {
        val result = aggregateThreadHistory(listOf(mention, root, first), "\$root", null, own)
        assertThat(result.unreadCount).isEqualTo(2)
        assertThat(result.mentionCount).isEqualTo(1)
        assertThat(result.participating).isTrue()
        assertThat(result.firstUnread).isEqualTo(first.eventId)
    }

    @Test
    fun `receipt excludes earlier replies and preserves event ordering`() {
        val result = aggregateThreadHistory(listOf(mention, root, first), "\$root", first.eventId, own)
        assertThat(result.unreadCount).isEqualTo(1)
        assertThat(result.firstUnread).isEqualTo(mention.eventId)
        assertThat(aggregateThreadHistory(listOf(root, first, mention), "\$root", mention.eventId, own).unreadCount).isEqualTo(0)
    }

    @Test(expected = IllegalStateException::class)
    fun `missing receipt is an explicit history failure`() {
        aggregateThreadHistory(listOf(root, mention), "\$root", first.eventId, own)
    }
}
