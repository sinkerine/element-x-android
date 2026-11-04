/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.ui.graph

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiBadgeTone

internal class InteractiveUiGraphLayoutPreviewParam : PreviewParameterProvider<InteractiveUiGraphLayout> {
    override val values = sequenceOf(false, true).map { vertical ->
        layoutLayeredGraph(InteractiveUiGraphInput(
        vertical,
            listOf("Ingredients", "Mechanisms", "Results"),
            listOf(
            InteractiveUiGraphInput.Node("sugar", "Sugar", null, 0, InteractiveUiBadgeTone.Info),
            InteractiveUiGraphInput.Node("maillard", "Maillard reaction", "Heat creates flavour", 1, InteractiveUiBadgeTone.Success),
            InteractiveUiGraphInput.Node("flavour", "Flavour", null, 2, InteractiveUiBadgeTone.Neutral),
        ),
            listOf(
            InteractiveUiGraphInput.Edge("sugar", "maillard", "Heated", false),
            InteractiveUiGraphInput.Edge("maillard", "flavour", "Produces", false),
            InteractiveUiGraphInput.Edge("flavour", "sugar", "Relationship with a wrapping label", true),
        ),
    ))
    }
}

@PreviewsDayNight
@Composable
internal fun InteractiveUiGraphViewPreview(@PreviewParameter(InteractiveUiGraphLayoutPreviewParam::class) layout: InteractiveUiGraphLayout) {
    ElementPreview { InteractiveUiGraphView(layout, null, false, true, {}, {}, Modifier.width(360.dp)) }
}

@PreviewsDayNight
@Composable
internal fun InteractiveUiGraphSelectedPreview(@PreviewParameter(InteractiveUiGraphLayoutPreviewParam::class) layout: InteractiveUiGraphLayout) {
    ElementPreview { InteractiveUiGraphView(layout, "maillard", false, true, {}, {}, Modifier.width(360.dp)) }
}

@PreviewsDayNight
@Composable
internal fun InteractiveUiGraphListPreview(@PreviewParameter(InteractiveUiGraphLayoutPreviewParam::class) layout: InteractiveUiGraphLayout) {
    ElementPreview { InteractiveUiGraphView(layout, null, true, false, {}, {}, Modifier.width(360.dp)) }
}

@Preview(fontScale = 2f, widthDp = 360)
@Composable
internal fun InteractiveUiGraphLargeFontPreview() {
    ElementPreview { InteractiveUiGraphView(InteractiveUiGraphLayoutPreviewParam().values.first(), "maillard", false, true, {}, {}) }
}
