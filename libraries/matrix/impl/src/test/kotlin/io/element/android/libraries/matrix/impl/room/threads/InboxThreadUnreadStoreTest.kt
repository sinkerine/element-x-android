/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.room.threads

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.room.threads.InboxThreadKey
import io.element.android.libraries.matrix.test.FakeMatrixClient
import kotlinx.coroutines.test.runTest
import org.junit.Test

class InboxThreadUnreadStoreTest {
    @Test
    fun `explicit unread persists separate thread keys and clearing preserves other threads`() = runTest {
        var stored: String? = null
        val client = FakeMatrixClient(
            getAccountDataLambda = { Result.success(stored) },
            setAccountDataLambda = { _, content ->
                stored = content
                Result.success(Unit)
            },
        )
        val store = InboxThreadUnreadStore(client)
        val one = InboxThreadKey(RoomId("!one:example.org"), ThreadId("\$one"))
        val two = one.copy(threadId = ThreadId("\$two"))
        store.set(one, true).getOrThrow()
        store.set(two, true).getOrThrow()
        assertThat(store.read()).containsExactly(one, two)
        store.set(one, false).getOrThrow()
        assertThat(store.read()).containsExactly(two)
    }

    @Test
    fun `failure to persist unread is returned to caller`() = runTest {
        val store = InboxThreadUnreadStore(FakeMatrixClient(
            getAccountDataLambda = { Result.success(null) },
            setAccountDataLambda = { _, _ -> Result.failure(IllegalStateException()) },
        ))
        assertThat(store.set(InboxThreadKey(RoomId("!one:example.org"), ThreadId("\$one")), true).isFailure).isTrue()
    }
}
