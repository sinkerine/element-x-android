/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl.roomlist

import com.google.common.truth.Truth.assertThat
import io.element.android.features.home.impl.filters.RoomListFilter
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.CurrentUserMembership
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_ROOM_ID_2
import io.element.android.libraries.matrix.test.A_ROOM_ID_3
import io.element.android.libraries.matrix.test.room.aRoomInfo
import io.element.android.libraries.matrix.test.room.aRoomSummary
import org.junit.Test

class UnreadRoomIdsTest {
    private val people = aRoomSummary(info = aRoomInfo(id = A_ROOM_ID, isDm = true, numUnreadMessages = 2))
    private val room = aRoomSummary(info = aRoomInfo(id = A_ROOM_ID_2, isFavorite = true, numUnreadMentions = 1))
    private val marked = aRoomSummary(info = aRoomInfo(id = A_ROOM_ID_3, isMarkedUnread = true))
    private val summaries = listOf(people, room, marked)

    @Test
    fun `people and rooms scopes are separate`() {
        assertThat(unreadRoomIds(summaries, listOf(RoomListFilter.People), null)).containsExactly(A_ROOM_ID)
        assertThat(unreadRoomIds(summaries, listOf(RoomListFilter.Rooms), null)).containsExactly(A_ROOM_ID_2, A_ROOM_ID_3)
        assertThat(unreadRoomIds(summaries, emptyList(), null)).containsExactly(A_ROOM_ID, A_ROOM_ID_2, A_ROOM_ID_3)
    }

    @Test
    fun `space and favourites narrow the scope including an empty space`() {
        assertThat(unreadRoomIds(summaries, listOf(RoomListFilter.Favourites), listOf(A_ROOM_ID, A_ROOM_ID_2)))
            .containsExactly(A_ROOM_ID_2)
        assertThat(unreadRoomIds(summaries, emptyList(), listOf(A_ROOM_ID_3))).containsExactly(A_ROOM_ID_3)
        assertThat(unreadRoomIds(summaries, emptyList(), emptyList())).isEmpty()
    }

    @Test
    fun `exclude invites spaces left rooms and already read rooms`() {
        val excluded = listOf(
            aRoomSummary(info = aRoomInfo(id = RoomId("!invite:test"), currentUserMembership = CurrentUserMembership.INVITED, isMarkedUnread = true)),
            aRoomSummary(info = aRoomInfo(id = RoomId("!left:test"), currentUserMembership = CurrentUserMembership.LEFT, isMarkedUnread = true)),
            aRoomSummary(info = aRoomInfo(id = RoomId("!space:test"), isSpace = true, isMarkedUnread = true)),
            aRoomSummary(info = aRoomInfo(id = RoomId("!read:test"))),
        )
        assertThat(unreadRoomIds(summaries + excluded + people, listOf(RoomListFilter.Unread), null))
            .containsExactly(A_ROOM_ID, A_ROOM_ID_2, A_ROOM_ID_3)
        assertThat(unreadRoomIds(summaries + excluded, listOf(RoomListFilter.Invites), null)).isEmpty()
    }
}
