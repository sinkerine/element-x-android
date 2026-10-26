/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.threads.api

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.room.threads.InboxThreadKey

interface ThreadsInboxEntryPoint {
    interface Callback : com.bumble.appyx.core.plugin.Plugin {
        fun openThread(key: InboxThreadKey, focusedEventId: EventId?, focusCaptureFailed: Boolean = false)
        fun openRoom(key: InboxThreadKey)
    }

    fun createNode(
        parentNode: com.bumble.appyx.core.node.Node,
        buildContext: com.bumble.appyx.core.modality.BuildContext,
        callback: Callback,
    ): com.bumble.appyx.core.node.Node

    @Composable
    fun Render(onOpen: (InboxThreadKey, EventId?, Boolean) -> Unit, onOpenInRoom: (InboxThreadKey) -> Unit, modifier: Modifier = Modifier)
}
