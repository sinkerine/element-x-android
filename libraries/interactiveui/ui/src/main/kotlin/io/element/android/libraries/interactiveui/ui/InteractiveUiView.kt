/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.components.dialogs.ConfirmationDialog
import io.element.android.libraries.designsystem.theme.components.Surface
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextButton
import io.element.android.libraries.interactiveui.core.session.InteractiveUiNotice
import io.element.android.libraries.interactiveui.core.session.InteractiveUiPhase
import io.element.android.libraries.interactiveui.core.session.InteractiveUiUrlRequest
import io.element.android.libraries.interactiveui.ui.components.InteractiveUiNodeView
import io.element.android.libraries.interactiveui.ui.components.InteractiveUiSwitch
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiViewEvent
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiViewState
import io.element.android.libraries.ui.strings.CommonStrings

/** Ported from element-web packages/shared-components/src/room/timeline/event-tile/body/InteractiveUiView/InteractiveUiView.tsx
 * at bcb08017 (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial).
 * [onShowMedia] reveals media using the room's timeline media protection policy.
 */
@Composable
fun InteractiveUiView(
    state: InteractiveUiViewState,
    onOpenUrl: (url: String) -> Unit,
    modifier: Modifier = Modifier,
    onShowMedia: () -> Unit = {},
    fallback: @Composable () -> Unit,
) {
    val latestState by rememberUpdatedState(state)
    val latestOpenUrl by rememberUpdatedState(onOpenUrl)
    LaunchedEffect(state.urlRequest, state.phase) {
        val request = latestState.urlRequest
        if (request != null && latestState.phase != InteractiveUiPhase.Active) {
            latestState.eventSink(InteractiveUiViewEvent.ClearUrlRequest)
        } else if (request is InteractiveUiUrlRequest.Approved) {
            latestState.eventSink(InteractiveUiViewEvent.ClearUrlRequest)
            latestOpenUrl(request.url)
        }
    }
    val request = state.urlRequest
    if (request is InteractiveUiUrlRequest.Confirming && state.phase == InteractiveUiPhase.Active) {
        ConfirmationDialog(
            title = stringResource(R.string.screen_interactive_ui_open_link_title),
            content = request.url,
            submitText = stringResource(CommonStrings.action_continue),
            onSubmitClick = { state.eventSink(InteractiveUiViewEvent.ConfirmOpenUrl) },
            onDismiss = { state.eventSink(InteractiveUiViewEvent.ClearUrlRequest) },
        )
    }
    Surface(
        modifier = modifier.widthIn(max = 680.dp).fillMaxWidth(),
        color = ElementTheme.colors.bgCanvasDefault,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, ElementTheme.colors.borderInteractiveSecondary),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.title?.let { Text(it, Modifier.semantics { heading() }, style = ElementTheme.typography.fontHeadingMdBold) }
            Status(state)
            InteractiveUiSwitch(stringResource(R.string.screen_interactive_ui_show_as_text), state.showAsText, true) {
                state.eventSink(InteractiveUiViewEvent.SetShowAsText(it))
            }
            if (state.showAsText) {
                fallback()
            } else {
                InteractiveUiNodeView(state.root, state.phase == InteractiveUiPhase.Active, state.eventSink, onShowMedia)
            }
        }
    }
}

@Composable
private fun Status(state: InteractiveUiViewState) {
    val message = when (val notice = state.notice) {
        is InteractiveUiNotice.PayloadText -> notice.text
        InteractiveUiNotice.SendFailed -> stringResource(R.string.screen_interactive_ui_send_failed)
        InteractiveUiNotice.WaitingForSender -> stringResource(R.string.screen_interactive_ui_waiting_for_sender, state.senderDisplayName)
        null -> when (state.phase) {
            InteractiveUiPhase.Active -> null
            InteractiveUiPhase.Sending -> stringResource(R.string.screen_interactive_ui_status_sending)
            InteractiveUiPhase.AwaitingConfirmation -> stringResource(R.string.screen_interactive_ui_status_awaiting_confirmation)
            InteractiveUiPhase.Submitted -> stringResource(R.string.screen_interactive_ui_status_submitted)
            InteractiveUiPhase.Expired -> stringResource(R.string.screen_interactive_ui_status_expired)
            InteractiveUiPhase.Superseded -> stringResource(R.string.screen_interactive_ui_status_superseded)
        }
    }
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        message?.let { Text(it, color = ElementTheme.colors.textSecondary) }
        if (state.confirmationOverdue) {
            TextButton(stringResource(CommonStrings.action_try_again), { state.eventSink(InteractiveUiViewEvent.RetryAfterOverdue) })
        }
    }
}
