/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components.event

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.messages.impl.R
import io.element.android.features.messages.impl.timeline.di.LocalTimelineItemPresenterFactories
import io.element.android.features.messages.impl.timeline.di.rememberPresenter
import io.element.android.features.messages.impl.timeline.model.event.InteractiveUiTimelineModel
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemInteractiveUiContent
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.interactiveui.ui.InteractiveUiView
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiViewState
import io.element.android.libraries.interactiveui.ui.withHiddenMedia
import io.element.android.libraries.ui.common.layout.ContentAvoidingLayoutData
import io.element.android.wysiwyg.link.Link

@Composable
fun TimelineItemInteractiveUiView(
    content: TimelineItemInteractiveUiContent,
    hideMedia: Boolean,
    onShowContentClick: () -> Unit,
    onLinkClick: (Link) -> Unit,
    onLinkLongClick: (Link) -> Unit,
    onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit,
    modifier: Modifier = Modifier,
    fallbackContent: @Composable () -> Unit = {
        TimelineItemTextView(content.fallback, onLinkClick, onLinkLongClick, onContentLayoutChange = onContentLayoutChange)
    },
) {
    when (val model = content.model) {
        is InteractiveUiTimelineModel.Valid -> {
            val presenter: Presenter<InteractiveUiViewState> = LocalTimelineItemPresenterFactories.current.rememberPresenter(content)
            InteractiveUiView(
                state = presenter.present().withHiddenMedia(hideMedia),
                onOpenUrl = { onLinkClick(Link(url = it, text = it)) },
                fallback = fallbackContent,
                modifier = modifier,
                onShowMedia = onShowContentClick,
            )
        }
        is InteractiveUiTimelineModel.Invalid -> Column(modifier) {
            fallbackContent()
            if (model.showCaption) {
                Text(
                stringResource(R.string.screen_interactive_ui_invalid_content),
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = ElementTheme.typography.fontBodySmRegular,
                    color = ElementTheme.colors.textSecondary,
            )
            }
        }
    }
}
