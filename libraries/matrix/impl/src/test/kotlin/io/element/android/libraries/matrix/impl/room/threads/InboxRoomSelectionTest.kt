/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.room.threads

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.CurrentUserMembership
import io.element.android.libraries.matrix.test.room.aRemoteLatestEvent
import io.element.android.libraries.matrix.test.room.aRoomSummary
import org.junit.Test

class InboxRoomSelectionTest {
    @Test
    fun `twenty subscriptions prefer known threads and unread over recent quiet rooms`() {
        val quiet = (1..30).map { aRoomSummary(roomId = RoomId("!quiet$it:example.org"), latestEvent = aRemoteLatestEvent(timestamp = it.toLong())) }
        val known = aRoomSummary(roomId = RoomId("!known:example.org"))
        val unread = aRoomSummary(roomId = RoomId("!unread:example.org"), numUnreadMessages = 1)
        val selected = selectInboxRooms(quiet + known + unread, setOf(known.roomId), emptyList())
        assertThat(selected).hasSize(20)
        assertThat(selected.take(2)).containsExactly(known, unread).inOrder()
    }

    @Test
    fun `left banned invited and spaces never open thread subscriptions`() {
        val rooms = listOf(
            aRoomSummary(roomId = RoomId("!left:example.org"), currentUserMembership = CurrentUserMembership.LEFT),
            aRoomSummary(roomId = RoomId("!banned:example.org"), currentUserMembership = CurrentUserMembership.BANNED),
            aRoomSummary(roomId = RoomId("!invited:example.org"), currentUserMembership = CurrentUserMembership.INVITED),
            aRoomSummary(roomId = RoomId("!space:example.org"), isSpace = true),
        )
        assertThat(selectInboxRooms(rooms, rooms.map { it.roomId }.toSet(), emptyList())).isEmpty()
    }

    @Test
    fun `recently opened known room survives the subscription cap`() {
        val rooms = (1..30).map { aRoomSummary(roomId = RoomId("!known$it:example.org")) }
        val recent = rooms.last().roomId
        val selected = selectInboxRooms(rooms, rooms.map { it.roomId }.toSet(), listOf(recent))
        assertThat(selected.first().roomId).isEqualTo(recent)
    }
}
