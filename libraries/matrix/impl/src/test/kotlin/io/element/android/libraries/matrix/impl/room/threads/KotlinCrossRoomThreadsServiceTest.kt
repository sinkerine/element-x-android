/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.room.threads

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.featureflag.test.FakeFeatureFlagService
import io.element.android.libraries.matrix.api.roomlist.RoomListService
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.room.aRoomSummary
import io.element.android.libraries.matrix.test.roomlist.FakeDynamicRoomList
import io.element.android.libraries.matrix.test.roomlist.FakeRoomListService
import io.element.android.libraries.preferences.test.InMemorySessionPreferencesStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KotlinCrossRoomThreadsServiceTest {
    @Test
    fun `startup holds unread state until first running sync`() = runTest {
        val list = FakeDynamicRoomList()
        val roomList = FakeRoomListService(allRooms = list)
        val client = FakeMatrixClient(sessionCoroutineScope = backgroundScope, roomListService = roomList)
        val service = KotlinCrossRoomThreadsService(client, InMemorySessionPreferencesStore(), InboxThreadUnreadStore(client), FakeFeatureFlagService())
        service.startVisit()
        runCurrent()
        assertThat(service.threads.value.isUninitialized()).isTrue()
        roomList.postState(RoomListService.State.Running)
        runCurrent()
        assertThat(service.threads.value.dataOrNull()).isEmpty()
        service.endVisit()
    }

    @Test
    fun `disabled cross room flag does not start background room discovery`() = runTest {
        val list =
            FakeDynamicRoomList(
                summaries = MutableStateFlow(listOf(aRoomSummary(roomId = io.element.android.libraries.matrix.api.core.RoomId("!candidate:example.org"))))
            )
        val roomList = FakeRoomListService(allRooms = list)
        val client = FakeMatrixClient(sessionCoroutineScope = backgroundScope, roomListService = roomList)
        val flags = FakeFeatureFlagService(initialState = mapOf(FeatureFlags.CrossRoomThreads.key to false))
        val service = KotlinCrossRoomThreadsService(client, InMemorySessionPreferencesStore(), InboxThreadUnreadStore(client), flags)
        roomList.postState(RoomListService.State.Running)
        runCurrent()
        assertThat(service.threads.value.isUninitialized()).isTrue()
    }
}
