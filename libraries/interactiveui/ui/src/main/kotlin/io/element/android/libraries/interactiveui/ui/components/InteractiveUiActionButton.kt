/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Surface
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiNode
import io.element.android.libraries.interactiveui.ui.R

/** Payload labels must wrap at large font sizes; the standard button restricts labels to one line. */
@Composable
internal fun ActionButton(
    label: String,
    enabled: Boolean,
    pending: Boolean,
    variant: InteractiveUiNode.ButtonVariant,
    external: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactive = enabled && !pending
    val outlined = variant == InteractiveUiNode.ButtonVariant.Secondary
    val background = when {
        outlined -> ElementTheme.colors.bgCanvasDefault
        !interactive -> ElementTheme.colors.bgActionPrimaryDisabled
        variant == InteractiveUiNode.ButtonVariant.Danger -> ElementTheme.colors.bgCriticalPrimary
        else -> ElementTheme.colors.bgAccentRest
    }
    val foreground = when {
        !interactive -> ElementTheme.colors.textDisabled
        outlined -> ElementTheme.colors.textPrimary
        else -> ElementTheme.colors.textOnSolidPrimary
    }
    Surface(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(enabled = interactive, role = Role.Button, onClick = onClick)
            .semantics { if (pending) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
        color = background,
        contentColor = foreground,
        border = if (outlined) BorderStroke(1.dp, ElementTheme.colors.borderInteractiveSecondary) else null,
        shape = RoundedCornerShape(50),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                label + if (external) " (" + stringResource(R.string.screen_interactive_ui_external_link) + ")" else "",
                color = foreground,
                style = ElementTheme.typography.fontBodyLgMedium,
                textAlign = TextAlign.Center,
            )
            if (pending) CircularProgressIndicator(Modifier.size(20.dp), color = foreground)
        }
    }
}
