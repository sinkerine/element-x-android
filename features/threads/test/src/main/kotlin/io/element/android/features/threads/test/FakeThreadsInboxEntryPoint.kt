package io.element.android.features.threads.test

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.element.android.features.threads.api.ThreadsInboxEntryPoint
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.room.threads.InboxThreadKey

class FakeThreadsInboxEntryPoint : ThreadsInboxEntryPoint {
    @Composable
    override fun Render(onOpen: (InboxThreadKey, EventId?) -> Unit, onOpenInRoom: (InboxThreadKey) -> Unit, modifier: Modifier) = Unit
}
