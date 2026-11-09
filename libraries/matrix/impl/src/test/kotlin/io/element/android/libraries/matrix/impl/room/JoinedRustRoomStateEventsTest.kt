/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.room

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.featureflag.test.FakeFeatureFlagService
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.room.RoomMembershipObserver
import io.element.android.libraries.matrix.api.room.RoomStateEvent
import io.element.android.libraries.matrix.api.room.StateEventType
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiRoom
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiRoomListService
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiThreadListService
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiTimeline
import io.element.android.libraries.matrix.test.A_DEVICE_ID
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.notificationsettings.FakeNotificationSettingsService
import io.element.android.libraries.matrix.test.room.aRoomInfo
import io.element.android.services.toolbox.test.systemclock.FakeSystemClock
import io.element.android.tests.testutils.testCoroutineDispatchers
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.matrix.rustcomponents.sdk.NoHandle
import org.matrix.rustcomponents.sdk.RoomStateEventsListener
import org.matrix.rustcomponents.sdk.TaskHandle
import uniffi.ruma_events.stateEventTypeFromString
import org.matrix.rustcomponents.sdk.RoomStateEvent as RustRoomStateEvent
import uniffi.ruma_events.StateEventType as RustStateEventType

class JoinedRustRoomStateEventsTest {
    private val eventType = StateEventType.Custom("org.matrix.msc4391.command_description")
    private val rustEventType = mockk<RustStateEventType.Custom>()

    @Before
    fun setUp() {
        // Custom SDK enum construction, string conversion and equality otherwise call native code.
        every { rustEventType.toString() } returns eventType.type
        mockkStatic(::stateEventTypeFromString)
        every { stateEventTypeFromString(eventType.type) } returns rustEventType
    }

    @After
    fun tearDown() {
        unmockkStatic(::stateEventTypeFromString)
    }

    @Test
    fun `maps state metadata and preserves raw content`() {
        assertThat(aStateEvent().map()).isEqualTo(
            RoomStateEvent(eventType, "name", UserId("@bot:example.org"), "{}", EventId("\$event"), 123L)
        )
        assertThat(aStateEvent().copy(eventId = null, timestamp = null).map()).isEqualTo(
            RoomStateEvent(eventType, "name", UserId("@bot:example.org"), "{}", null, null)
        )
    }

    @Test
    fun `maps custom state type`() {
        val type = mockk<RustStateEventType.Custom>()
        every { type.toString() } returns "org.matrix.msc4391.command_description"
        assertThat(aStateEvent().copy(eventType = type).map()?.eventType)
            .isEqualTo(StateEventType.Custom("org.matrix.msc4391.command_description"))
    }

    @Test
    fun `drops invalid senders`() {
        assertThat(aStateEvent().copy(sender = "invalid").map()).isNull()
    }

    @Test
    fun `emits initial SDK snapshot and updates and destroys subscription on cancellation`() = runTest {
        var listener: RoomStateEventsListener? = null
        var cancelled = false
        var destroyed = false
        val handle = object : TaskHandle(NoHandle) {
            override fun cancel() {
                cancelled = true
            }
            override fun destroy() {
                destroyed = true
            }
        }
        val room = createJoinedRustRoom(
            FakeFfiRoom(
                threadListServiceLambda = { FakeFfiThreadListService() },
                subscribeToStateEventsLambda = { eventType, callback ->
                    assertThat(eventType).isSameInstanceAs(rustEventType)
                    listener = callback
                    callback.onUpdate(listOf(aStateEvent(), aStateEvent().copy(sender = "invalid")))
                    handle
                },
            )
        )
        room.stateEventsFlow(eventType).test {
            assertThat(awaitItem()).containsExactly(aStateEvent().map())
            listener!!.onUpdate(listOf(aStateEvent().copy(contentJson = "{\"name\":\"updated\"}")))
            assertThat(awaitItem().single().contentJson).isEqualTo("{\"name\":\"updated\"}")
            listener.onUpdate(emptyList())
            assertThat(awaitItem()).isEmpty()
            assertThat(cancelled).isFalse()
            assertThat(destroyed).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(cancelled).isTrue()
        assertThat(destroyed).isTrue()
    }

    private fun aStateEvent() = RustRoomStateEvent(
        eventType = rustEventType,
        stateKey = "name",
        sender = "@bot:example.org",
        contentJson = "{}",
        eventId = "\$event",
        timestamp = 123uL,
    )

    private fun TestScope.createJoinedRustRoom(
        innerRoom: FakeFfiRoom,
    ): JoinedRustRoom {
        val dispatchers = testCoroutineDispatchers()
        val roomListService = FakeFfiRoomListService()
        val baseRoom = RustBaseRoom(
            sessionId = A_SESSION_ID,
            deviceId = A_DEVICE_ID,
            innerRoom = innerRoom,
            coroutineDispatchers = dispatchers,
            roomSyncSubscriber = RoomSyncSubscriber(roomListService, dispatchers),
            roomMembershipObserver = RoomMembershipObserver(),
            sessionCoroutineScope = backgroundScope,
            roomInfoMapper = RoomInfoMapper(),
            initialRoomInfo = aRoomInfo(),
        )
        return JoinedRustRoom(
            baseRoom = baseRoom,
            liveInnerTimeline = FakeFfiTimeline(),
            notificationSettingsService = FakeNotificationSettingsService(),
            coroutineDispatchers = dispatchers,
            systemClock = FakeSystemClock(),
            roomContentForwarder = RoomContentForwarder(roomListService),
            featureFlagService = FakeFeatureFlagService(),
        )
    }
}
