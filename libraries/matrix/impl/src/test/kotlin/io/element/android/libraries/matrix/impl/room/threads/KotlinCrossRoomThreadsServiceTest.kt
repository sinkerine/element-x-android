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
import io.element.android.libraries.matrix.test.roomlist.FakeRoomListService
import io.element.android.libraries.preferences.test.InMemorySessionPreferencesStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.matrix.rustcomponents.sdk.ThreadInboxRoom

@OptIn(ExperimentalCoroutinesApi::class)
class KotlinCrossRoomThreadsServiceTest {
    private class Source : ThreadInboxSource {
        val invalidations = Channel<List<String>?>(Channel.UNLIMITED)
        override val updates = invalidations.receiveAsFlow()
        val requests = mutableListOf<List<String>?>()
        var failure = false
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun load(roomIds: List<String>?): List<ThreadInboxRoom> {
            requests.add(roomIds)
            gate?.await()
            check(!failure) { "Discovery failed" }
            return List(30) { ThreadInboxRoom("!quiet$it:example.org", emptyList()) }
        }
    }

    @Test
    fun `startup holds unread state until first running sync then scans full account`() = runTest {
        val roomList = FakeRoomListService()
        val client = FakeMatrixClient(sessionCoroutineScope = backgroundScope, roomListService = roomList, getAccountDataLambda = { Result.success(null) })
        val source = Source()
        val service = KotlinCrossRoomThreadsService(
            client,
            InMemorySessionPreferencesStore(),
            InboxThreadUnreadStore(client),
            FakeFeatureFlagService(),
            source,
        )
        service.startVisit()
        runCurrent()
        assertThat(source.requests).isEmpty()
        assertThat(service.threads.value.isUninitialized()).isTrue()
        roomList.postState(RoomListService.State.Running)
        runCurrent()
        assertThat(source.requests).containsExactly(null)
        assertThat(service.threads.value.dataOrNull()).isEmpty()
        assertThat(service.threads.value.isSuccess()).isTrue()
    }

    @Test
    fun `failed account refresh preserves complete cached snapshot and retry scans all rooms`() = runTest {
        val roomList = FakeRoomListService()
        val client = FakeMatrixClient(sessionCoroutineScope = backgroundScope, roomListService = roomList, getAccountDataLambda = { Result.success(null) })
        val source = Source()
        val service = KotlinCrossRoomThreadsService(
            client,
            InMemorySessionPreferencesStore(),
            InboxThreadUnreadStore(client),
            FakeFeatureFlagService(),
            source,
        )
        roomList.postState(RoomListService.State.Running)
        runCurrent()
        source.failure = true
        source.invalidations.send(listOf("!quiet29:example.org"))
        runCurrent()
        assertThat(source.requests.last()).containsExactly("!quiet29:example.org")
        assertThat(service.threads.value.isFailure()).isTrue()
        assertThat(service.threads.value.dataOrNull()).isEmpty()
        source.failure = false
        service.startVisit()
        runCurrent()
        assertThat(source.requests.last()).isNull()
        assertThat(service.threads.value.isSuccess()).isTrue()
    }

    @Test
    fun `receipt update during initial discovery is processed after complete snapshot`() = runTest {
        val roomList = FakeRoomListService()
        val client = FakeMatrixClient(sessionCoroutineScope = backgroundScope, roomListService = roomList, getAccountDataLambda = { Result.success(null) })
        val source = Source().apply { gate = CompletableDeferred() }
        val service = KotlinCrossRoomThreadsService(
            client,
            InMemorySessionPreferencesStore(),
            InboxThreadUnreadStore(client),
            FakeFeatureFlagService(),
            source,
        )
        roomList.postState(RoomListService.State.Running)
        runCurrent()
        assertThat(service.threads.value.isUninitialized()).isTrue()
        source.invalidations.send(listOf("!quiet29:example.org"))
        runCurrent()
        source.gate!!.complete(Unit)
        runCurrent()
        // A concurrent full invalidation can subsume the changed-room request.
        assertThat(source.requests).hasSize(2)
        assertThat(source.requests.first()).isNull()
        assertThat(source.requests.last() == null || source.requests.last() == listOf("!quiet29:example.org")).isTrue()
        assertThat(service.threads.value.isSuccess()).isTrue()
    }

    @Test
    fun `warm visit and scrolling do not repeat exhaustive discovery`() = runTest {
        val roomList = FakeRoomListService()
        val client = FakeMatrixClient(sessionCoroutineScope = backgroundScope, roomListService = roomList, getAccountDataLambda = { Result.success(null) })
        val source = Source()
        val service = KotlinCrossRoomThreadsService(
            client,
            InMemorySessionPreferencesStore(),
            InboxThreadUnreadStore(client),
            FakeFeatureFlagService(),
            source,
        )
        roomList.postState(RoomListService.State.Running)
        runCurrent()
        source.requests.clear()
        service.startVisit()
        service.paginate().getOrThrow()
        service.endVisit()
        runCurrent()
        assertThat(source.requests).isEmpty()
    }

    @Test
    fun `disabled cross room flag does not start discovery and enabling triggers full scan`() = runTest {
        val roomList = FakeRoomListService()
        val client = FakeMatrixClient(sessionCoroutineScope = backgroundScope, roomListService = roomList, getAccountDataLambda = { Result.success(null) })
        val flags = FakeFeatureFlagService(initialState = mapOf(FeatureFlags.CrossRoomThreads.key to false))
        val source = Source()
        val service = KotlinCrossRoomThreadsService(client, InMemorySessionPreferencesStore(), InboxThreadUnreadStore(client), flags, source)
        roomList.postState(RoomListService.State.Running)
        service.startVisit()
        runCurrent()
        assertThat(source.requests).isEmpty()
        assertThat(service.threads.value.isUninitialized()).isTrue()
        flags.setFeatureEnabled(FeatureFlags.CrossRoomThreads, true)
        runCurrent()
        assertThat(source.requests).containsExactly(null)
    }
}
