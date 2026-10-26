/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.threads.impl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.element.android.features.threads.api.ThreadsInboxEntryPoint
import io.element.android.features.threads.api.ThreadsTriageQueue
import io.element.android.libraries.architecture.createNode
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.room.threads.InboxThreadKey
import kotlinx.coroutines.launch

@ContributesBinding(SessionScope::class)
@Inject
class DefaultThreadsInboxEntryPoint(private val presenter: ThreadsInboxPresenter, private val queue: ThreadsTriageQueue) : ThreadsInboxEntryPoint {
    override fun createNode(
        parentNode: com.bumble.appyx.core.node.Node,
        buildContext: com.bumble.appyx.core.modality.BuildContext,
        callback: ThreadsInboxEntryPoint.Callback,
    ): com.bumble.appyx.core.node.Node = parentNode.createNode<ThreadsInboxNode>(buildContext, listOf(callback))

    @Composable
    override fun Render(onOpen: (InboxThreadKey, EventId?, Boolean) -> Unit, onOpenInRoom: (InboxThreadKey) -> Unit, modifier: Modifier) {
        val scope = rememberCoroutineScope()
        ThreadsInboxView(
            state = presenter.present(),
            onOpen = { key ->
                scope.launch {
                val captured = queue.open(key)
                onOpen(key, captured.getOrNull(), captured.isFailure)
            }
            },
            onOpenInRoom = onOpenInRoom,
            modifier = modifier,
        )
    }
}
