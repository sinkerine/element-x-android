package io.element.android.features.threads.test

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.element.android.features.threads.api.ThreadsInboxEntryPoint
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.room.threads.InboxThreadKey

class FakeThreadsInboxEntryPoint : ThreadsInboxEntryPoint {
    override fun createNode(
        parentNode: com.bumble.appyx.core.node.Node,
        buildContext: com.bumble.appyx.core.modality.BuildContext,
        callback: ThreadsInboxEntryPoint.Callback,
    ): com.bumble.appyx.core.node.Node = com.bumble.appyx.core.node.node(buildContext) {}

    @Composable
    override fun Render(onOpen: (InboxThreadKey, EventId?, Boolean) -> Unit, onOpenInRoom: (InboxThreadKey) -> Unit, modifier: Modifier) = Unit
}
