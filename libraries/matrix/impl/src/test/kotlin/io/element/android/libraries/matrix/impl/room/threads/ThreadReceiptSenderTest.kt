/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.room.threads

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.room.RoomNotificationMode
import io.element.android.libraries.matrix.api.room.threads.InboxThread
import io.element.android.libraries.matrix.api.room.threads.InboxThreadKey
import io.element.android.libraries.matrix.api.room.threads.ThreadListItemEvent
import io.element.android.libraries.matrix.api.timeline.item.event.ProfileDetails
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.matrix.rustcomponents.sdk.ReceiptThread
import org.matrix.rustcomponents.sdk.ReceiptType

class ThreadReceiptSenderTest {
    private val root = ThreadListItemEvent(EventId("\$root"), UserId("@own:example.org"), ProfileDetails.Unavailable, true, null, 0)
    private val snapshot = InboxThread(
        InboxThreadKey(RoomId("!room:example.org"), ThreadId("\$root")),
        null,
        null,
        root,
        root.copy(eventId = EventId("\$captured")),
        2,
        1,
        0,
        true,
        RoomNotificationMode.ALL_MESSAGES,
        null,
    )

    @Test
    fun `private receipt is always threaded and uses the captured latest event`() = runTest {
        var calls = 0
        sendCapturedThreadReceipt(snapshot, false) { type, thread, event ->
            calls++
            assertThat(type).isEqualTo(ReceiptType.READ_PRIVATE)
            assertThat(thread).isEqualTo(ReceiptThread.Thread("\$root"))
            assertThat(event).isEqualTo("\$captured")
        }
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `public receipt uses the same thread target without a main timeline receipt`() = runTest {
        sendCapturedThreadReceipt(snapshot, true) { type, thread, event ->
            assertThat(type).isEqualTo(ReceiptType.READ)
            assertThat(thread).isEqualTo(ReceiptThread.Thread("\$root"))
            assertThat(event).isEqualTo("\$captured")
        }
    }
}
