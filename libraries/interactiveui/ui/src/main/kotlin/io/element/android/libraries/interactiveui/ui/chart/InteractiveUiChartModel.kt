/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.ui.chart

import kotlinx.collections.immutable.ImmutableList

/** Ported from element-web packages/shared-components/src/room/timeline/event-tile/body/InteractiveUiView/chart/ChartModel.ts
 * at bcb08017 (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial).
 */
sealed interface InteractiveUiChartModel {
    val title: String?
    val unit: String?
    data class Categorical(
        override val title: String?, override val unit: String?, val line: Boolean, val xLabel: String?, val yLabel: String?,
        val categories: ImmutableList<String>, val series: ImmutableList<Series>,
    ) : InteractiveUiChartModel
    data class Pie(
        override val title: String?, override val unit: String?, val categories: ImmutableList<String>, val values: ImmutableList<Double>,
    ) : InteractiveUiChartModel
    data class Scatter(
        override val title: String?, override val unit: String?, val xLabel: String?, val yLabel: String?, val series: ImmutableList<ScatterSeries>,
    ) : InteractiveUiChartModel
    data class Series(val name: String, val values: ImmutableList<Double>)
    data class ScatterSeries(val name: String, val points: ImmutableList<Triple<Double, Double, String?>>)
}
