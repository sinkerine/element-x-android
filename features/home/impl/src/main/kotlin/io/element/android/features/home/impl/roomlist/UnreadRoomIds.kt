/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl.roomlist

import io.element.android.features.home.impl.filters.RoomListFilter
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.CurrentUserMembership
import io.element.android.libraries.matrix.api.roomlist.RoomSummary

internal fun unreadRoomIds(
    summaries: List<RoomSummary>,
    filters: List<RoomListFilter>,
    spaceRoomIds: Collection<RoomId>?,
): List<RoomId> = summaries.filter { summary ->
    val info = summary.info
    info.currentUserMembership == CurrentUserMembership.JOINED &&
        !info.isSpace &&
        (spaceRoomIds == null || summary.roomId in spaceRoomIds) &&
        (info.isMarkedUnread || info.numUnreadMessages > 0 || info.numUnreadNotifications > 0 || info.numUnreadMentions > 0) &&
        filters.all { filter ->
            when (filter) {
                RoomListFilter.People -> info.isDm
                RoomListFilter.Rooms -> !info.isDm
                RoomListFilter.Favourites -> info.isFavorite
                RoomListFilter.Invites -> false
                RoomListFilter.Unread -> true
            }
        }
}.map { it.roomId }.distinct()
