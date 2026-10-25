/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.threads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.ui.strings.CommonStrings

@Composable
internal fun UpNextBar(preview: String?, count: Int, onNext: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(CommonStrings.screen_threads_inbox_up_next)
    val summary = preview ?: stringResource(CommonStrings.screen_threads_inbox_caught_up_title)
    val action = if (preview == null) {
        stringResource(CommonStrings.screen_threads_inbox_all_read)
    } else {
        stringResource(CommonStrings.screen_threads_inbox_next, count)
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(label, style = ElementTheme.typography.fontBodySmMedium)
        Text(
            summary,
            modifier = Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "$label, $summary, $action"
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = ElementTheme.typography.fontBodyMdRegular,
        )
        TextButton(onClick = onNext, enabled = preview != null) {
            Text(action)
        }
    }
}

internal class UpNextBarPreviewParam : androidx.compose.ui.tooling.preview.PreviewParameterProvider<Pair<String?, Int>> {
    override val values = sequenceOf("Android team · Lena: Could you mock this up?" to 3, null to 0)
}

@PreviewsDayNight
@Composable
internal fun UpNextBarPreview(
    @androidx.compose.ui.tooling.preview.PreviewParameter(UpNextBarPreviewParam::class) state: Pair<String?, Int>,
) = ElementPreview {
    UpNextBar(state.first, state.second, {})
}
