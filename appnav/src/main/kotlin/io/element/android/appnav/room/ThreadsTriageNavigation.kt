/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.appnav.room

import com.bumble.appyx.navmodel.backstack.BackStack
import com.bumble.appyx.navmodel.backstack.operation.replace
import io.element.android.appnav.LoggedInFlowNode
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.core.toRoomIdOrAlias

/** A triage visit owns one outer room slot, regardless of how many threads are opened. */
internal fun BackStack<LoggedInFlowNode.NavTarget>.replaceTriageThread(roomId: RoomId, threadId: ThreadId, focusedEventId: EventId?) {
    replace(LoggedInFlowNode.NavTarget.Room(
        roomIdOrAlias = roomId.toRoomIdOrAlias(),
        initialElement = RoomNavigationTarget.Thread(threadId, focusedEventId),
    ))
}
