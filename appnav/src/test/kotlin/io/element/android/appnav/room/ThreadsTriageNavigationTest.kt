/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.appnav.room

import com.bumble.appyx.navmodel.backstack.BackStack
import com.bumble.appyx.navmodel.backstack.activeElement
import com.bumble.appyx.navmodel.backstack.operation.pop
import com.bumble.appyx.navmodel.backstack.operation.push
import com.google.common.truth.Truth.assertThat
import io.element.android.appnav.LoggedInFlowNode
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.core.toRoomIdOrAlias
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Test

class ThreadsTriageNavigationTest : RobolectricTest() {
    @Test
    fun `capture failure survives Next replacement into room navigation`() {
        val backstack = BackStack<LoggedInFlowNode.NavTarget>(initialElement = LoggedInFlowNode.NavTarget.Home, savedStateMap = null)
        backstack.replaceTriageThread(RoomId("!room:example.org"), ThreadId("\$root"), null, focusCaptureFailed = true)
        val room = backstack.activeElement as LoggedInFlowNode.NavTarget.Room
        assertThat(room.initialElement).isEqualTo(RoomNavigationTarget.Thread(ThreadId("\$root"), null, focusCaptureFailed = true))
    }

    @Test
    fun `Next replaces two threads and one Back returns directly to Home`() {
        val home = LoggedInFlowNode.NavTarget.Home
        val backstack = BackStack<LoggedInFlowNode.NavTarget>(initialElement = home, savedStateMap = null)
        backstack.push(LoggedInFlowNode.NavTarget.Room(
            RoomId("!first:example.org").toRoomIdOrAlias(),
            initialElement = RoomNavigationTarget.Thread(ThreadId("\$one"), EventId("\$first-unread")),
        ))
        backstack.replaceTriageThread(RoomId("!second:example.org"), ThreadId("\$two"), EventId("\$second-unread"))
        backstack.replaceTriageThread(RoomId("!third:example.org"), ThreadId("\$three"), null)
        val room = backstack.activeElement as LoggedInFlowNode.NavTarget.Room
        assertThat(room.roomIdOrAlias).isEqualTo(RoomId("!third:example.org").toRoomIdOrAlias())
        assertThat(room.initialElement).isEqualTo(RoomNavigationTarget.Thread(ThreadId("\$three"), null))
        backstack.pop()
        assertThat(backstack.activeElement).isEqualTo(home)
    }
}
