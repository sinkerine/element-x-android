/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextButton
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiBadgeTone
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.ui.media.MediaRequestData
import io.element.android.libraries.ui.strings.CommonStrings

/** Full content requests preserve encrypted sources for the Rust media loader. */
@Composable
internal fun InteractiveUiImage(alt: String, source: MediaSource?, ratio: Float?, hidden: Boolean, onShowMedia: () -> Unit) {
    var failed by remember(source) { mutableStateOf(false) }
    if (hidden || source == null || failed) {
        Column {
            Text(alt, color = ElementTheme.colors.textSecondary)
            if (hidden) TextButton(stringResource(CommonStrings.action_show), onShowMedia)
        }
    } else {
        AsyncImage(
            model = MediaRequestData(source, MediaRequestData.Kind.Content),
            contentDescription = alt,
            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).aspectRatio(ratio?.takeIf { it.isFinite() && it > 0 } ?: 1.6f),
            contentScale = ContentScale.Fit,
            onError = { failed = true },
        )
    }
}

@Composable
internal fun InteractiveUiBadge(text: String, tone: InteractiveUiBadgeTone) {
    val background = when (tone) {
        InteractiveUiBadgeTone.Neutral -> ElementTheme.colors.bgSubtleSecondary
        InteractiveUiBadgeTone.Info -> ElementTheme.colors.bgInfoSubtle
        InteractiveUiBadgeTone.Success -> ElementTheme.colors.bgSuccessSubtle
        InteractiveUiBadgeTone.Warning -> ElementTheme.colors.bgSubtleSecondary
        InteractiveUiBadgeTone.Critical -> ElementTheme.colors.bgCriticalSubtle
    }
    val foreground = when (tone) {
        InteractiveUiBadgeTone.Neutral -> ElementTheme.colors.textSecondary
        InteractiveUiBadgeTone.Info -> ElementTheme.colors.textInfoPrimary
        InteractiveUiBadgeTone.Success -> ElementTheme.colors.textSuccessPrimary
        InteractiveUiBadgeTone.Warning -> ElementTheme.colors.textWarningPrimary
        InteractiveUiBadgeTone.Critical -> ElementTheme.colors.textCriticalPrimary
    }
    io.element.android.libraries.designsystem.theme.components.Surface(color = background, shape = RoundedCornerShape(8.dp)) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = foreground, style = ElementTheme.typography.fontBodySmRegular)
    }
}
