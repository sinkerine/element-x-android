/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.room

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.featureflag.test.FakeFeatureFlagService
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.room.RoomMembershipObserver
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.matrix.rustcomponents.sdk.RequestConfigOverride
import kotlin.coroutines.cancellation.CancellationException

class JoinedRustRoomSendRawTest {
    @Test
    fun `sendRaw forwards the exact event type and JSON with no request override and returns the event id`() = runTest {
        val eventType = "m.room.message"
        val content = """{"msgtype":"m.text","body":"Response","net.15cm.matrix.interactive_ui.action":{"version":1}}"""
        var calls = 0
        val room = createJoinedRustRoom { actualType, actualContent, requestConfigOverride ->
            calls++
            assertThat(actualType).isEqualTo(eventType)
            assertThat(actualContent).isEqualTo(content)
            assertThat(requestConfigOverride).isNull()
            "\$accepted:example.org"
        }

        assertThat(room.sendRaw(eventType, content).getOrThrow()).isEqualTo(EventId("\$accepted:example.org"))
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `sendRaw contains SDK exceptions in a failed result`() = runTest {
        val failure = IllegalStateException("Send failed")
        val room = createJoinedRustRoom { _, _, _ -> throw failure }

        assertThat(room.sendRaw("custom.event", "{}").exceptionOrNull()).isSameInstanceAs(failure)
    }

    @Test
    fun `sendRaw rethrows SDK cancellation`() = runTest {
        val cancellation = CancellationException("Send cancelled")
        val room = createJoinedRustRoom { _, _, _ -> throw cancellation }

        val thrown = try {
            room.sendRaw("custom.event", "{}")
            null
        } catch (exception: CancellationException) {
            exception
        }

        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        assertThat(thrown).hasMessageThat().isEqualTo(cancellation.message)
    }

    @Test
    fun `cancelling the caller cancels an in-flight SDK send`() = runTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val room = createJoinedRustRoom { _, _, _ ->
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        var returned = false
        val send = launch {
            room.sendRaw("custom.event", "{}")
            returned = true
        }
        started.await()

        send.cancel()
        send.join()

        assertThat(cancelled.isCompleted).isTrue()
        assertThat(returned).isFalse()
    }

    @Test
    fun `sendRaw does not contain fatal errors`() = runTest {
        val failure = AssertionError("Fatal error")
        val room = createJoinedRustRoom { _, _, _ -> throw failure }

        val thrown = try {
            room.sendRaw("custom.event", "{}")
            null
        } catch (error: AssertionError) {
            error
        }

        assertThat(thrown).isInstanceOf(AssertionError::class.java)
        assertThat(thrown).hasMessageThat().isEqualTo(failure.message)
    }

    private fun TestScope.createJoinedRustRoom(
        sendRaw: suspend (String, String, RequestConfigOverride?) -> String,
    ): JoinedRustRoom {
        val dispatchers = testCoroutineDispatchers()
        val roomListService = FakeFfiRoomListService()
        val baseRoom = RustBaseRoom(
            sessionId = A_SESSION_ID,
            deviceId = A_DEVICE_ID,
            innerRoom = FakeFfiRoom(
                sendRawLambda = sendRaw,
                threadListServiceLambda = { FakeFfiThreadListService() },
            ),
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
