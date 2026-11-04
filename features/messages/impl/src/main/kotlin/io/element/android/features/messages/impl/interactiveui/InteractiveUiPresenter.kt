/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.interactiveui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.element.android.features.messages.impl.timeline.di.TimelineItemPresenterFactory
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemInteractiveUiContent
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.interactiveui.core.session.InteractiveUiSession
import io.element.android.libraries.interactiveui.ui.InteractiveUiRenderTreeBuilder
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiViewEvent
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiViewState

@AssistedInject
class InteractiveUiPresenter(
    @Assisted private val content: TimelineItemInteractiveUiContent,
    private val registry: InteractiveUiSessionRegistry,
) : Presenter<InteractiveUiViewState> {
    @AssistedFactory
    fun interface Factory : TimelineItemPresenterFactory<TimelineItemInteractiveUiContent, InteractiveUiViewState> {
        override fun create(content: TimelineItemInteractiveUiContent): InteractiveUiPresenter
    }

    @Composable
    override fun present(): InteractiveUiViewState {
        val entry = registry.sessionFor(content)
        val state by entry.state.collectAsState()
        return InteractiveUiRenderTreeBuilder.build(
            state.sessionState,
            state.model.richText,
            state.model.media,
            false,
            content.senderDisplayName
        ) { entry.session.handle(it) }
    }
}

private fun InteractiveUiSession.handle(event: InteractiveUiViewEvent) {
    when (event) {
        is InteractiveUiViewEvent.SetValue -> setValue(event.nodeId, event.value)
        is InteractiveUiViewEvent.Activate -> activate(event.nodeId)
        is InteractiveUiViewEvent.SubmitForm -> submitForm(event.nodeId)
        is InteractiveUiViewEvent.SelectGraphNode -> selectGraphNode(event.nodeId, event.graphNodeId)
        is InteractiveUiViewEvent.SetGraphListMode -> setGraphListMode(event.nodeId, event.asList)
        is InteractiveUiViewEvent.SetChartDataMode -> setChartDataMode(event.nodeId, event.showData)
        is InteractiveUiViewEvent.SortTable -> sortTable(event.nodeId, event.columnKey)
        is InteractiveUiViewEvent.SetShowAsText -> setShowAsText(event.showAsText)
        is InteractiveUiViewEvent.SetCarouselIndex -> setCarouselIndex(event.nodeId, event.index)
        is InteractiveUiViewEvent.ActivateCarouselItem -> activateCarouselItem(event.nodeId, event.itemId)
        is InteractiveUiViewEvent.OpenLink -> openLink(event.nodeId)
        InteractiveUiViewEvent.ConfirmOpenUrl -> confirmOpenUrl()
        InteractiveUiViewEvent.ClearUrlRequest -> clearUrlRequest()
        InteractiveUiViewEvent.RetryAfterOverdue -> retryAfterOverdue()
    }
}
