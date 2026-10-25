/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.room.threads

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.impl.RustMatrixClient
import io.element.android.libraries.matrix.impl.util.mxCallbackFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import org.matrix.rustcomponents.sdk.ThreadInboxListener
import org.matrix.rustcomponents.sdk.ThreadInboxRoom

interface ThreadInboxSource {
    val updates: Flow<List<String>?>
    suspend fun load(roomIds: List<String>?): List<ThreadInboxRoom>
}

@ContributesBinding(SessionScope::class)
@Inject
class RustThreadInboxSource(private val client: MatrixClient) : ThreadInboxSource {
    private val inner get() = (client as RustMatrixClient).innerClient
    override val updates: Flow<List<String>?> = mxCallbackFlow {
        inner.subscribeToThreadInboxUpdates(object : ThreadInboxListener {
            override fun onUpdate(roomIds: List<String>?) {
                trySend(roomIds)
            }
        })
    }.buffer(Channel.UNLIMITED)

    override suspend fun load(roomIds: List<String>?): List<ThreadInboxRoom> = inner.loadThreadInbox(roomIds)
}
