/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.ui.chart

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import kotlinx.collections.immutable.persistentListOf

internal class InteractiveUiChartModelPreviewParam : PreviewParameterProvider<InteractiveUiChartModel> {
    override val values = sequenceOf(
        InteractiveUiChartModel.Categorical("Example data", "kg", false, null, null, persistentListOf("A", "B"), persistentListOf(
            InteractiveUiChartModel.Series("Mass", persistentListOf(2.0, 3.0)),
            InteractiveUiChartModel.Series("Target", persistentListOf(-1.0, 4.0)),
        )),
        InteractiveUiChartModel.Categorical("Example data", "kg", true, "Category", "Mass", persistentListOf("A", "B"), persistentListOf(
            InteractiveUiChartModel.Series("Mass", persistentListOf(2.0, 3.0)),
            InteractiveUiChartModel.Series("Target", persistentListOf(1.0, 4.0)),
        )),
        InteractiveUiChartModel.Scatter("Example data", "kg", null, null, persistentListOf(
            InteractiveUiChartModel.ScatterSeries("Mass", persistentListOf(Triple(1.0, 2.0, "A"), Triple(2.0, 3.0, null))),
        )),
        InteractiveUiChartModel.Pie("Example data", "kg", persistentListOf("A", "B"), persistentListOf(2.0, 3.0)),
        InteractiveUiChartModel.Pie("Single value", null, persistentListOf("A"), persistentListOf(2.0)),
        InteractiveUiChartModel.Pie("Negative values", null, persistentListOf("A", "B"), persistentListOf(-2.0, 3.0)),
        InteractiveUiChartModel.Pie("Zero total", null, persistentListOf("A", "B"), persistentListOf(0.0, 0.0)),
    )
}

@PreviewsDayNight
@Composable
internal fun InteractiveUiChartViewPreview(@PreviewParameter(InteractiveUiChartModelPreviewParam::class) chart: InteractiveUiChartModel) {
    ElementPreview { InteractiveUiChartView(chart, false, true, {}, Modifier.width(360.dp)) }
}

@PreviewsDayNight
@Composable
internal fun InteractiveUiChartDataPreview(@PreviewParameter(InteractiveUiChartModelPreviewParam::class) chart: InteractiveUiChartModel) {
    ElementPreview { InteractiveUiChartView(chart, true, false, {}, Modifier.width(360.dp)) }
}

@Preview(fontScale = 2f, widthDp = 360)
@Composable
internal fun InteractiveUiChartLargeFontPreview() {
    ElementPreview { InteractiveUiChartView(InteractiveUiChartModelPreviewParam().values.first(), false, true, {}) }
}
