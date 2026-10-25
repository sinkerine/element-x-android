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
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.plugin.Plugin
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedInject
import io.element.android.annotations.ContributesNode
import io.element.android.features.threads.api.ThreadsInboxEntryPoint
import io.element.android.features.threads.api.ThreadsTriageQueue
import io.element.android.libraries.architecture.callback
import io.element.android.libraries.di.SessionScope
import kotlinx.coroutines.launch

@ContributesNode(SessionScope::class)
@AssistedInject
class ThreadsInboxNode(
    @Assisted buildContext: BuildContext,
    @Assisted plugins: List<Plugin>,
    private val presenter: ThreadsInboxPresenter,
    private val queue: ThreadsTriageQueue,
) : Node(buildContext, plugins = plugins) {
    private val callback: ThreadsInboxEntryPoint.Callback = callback()

    @Composable
    override fun View(modifier: Modifier) {
        val scope = rememberCoroutineScope()
        ThreadsInboxView(
            state = presenter.present(),
            onOpen = { key -> scope.launch { callback.openThread(key, queue.open(key).getOrNull()) } },
            onOpenInRoom = callback::openRoom,
            modifier = modifier,
        )
    }
}
