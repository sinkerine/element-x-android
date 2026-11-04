/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.ui.chart

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.interactiveui.ui.R
import io.element.android.libraries.interactiveui.ui.table.InteractiveUiCell
import io.element.android.libraries.interactiveui.ui.table.InteractiveUiTableColumn
import io.element.android.libraries.interactiveui.ui.table.InteractiveUiTableView
import io.element.android.libraries.interactiveui.ui.table.displayNumber
import kotlinx.collections.immutable.toImmutableList

/** Ported from element-web packages/shared-components/src/room/timeline/event-tile/body/InteractiveUiView/chart/InteractiveUiChartView.tsx
 * at bcb08017 (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial).
 */
@Composable
fun InteractiveUiChartView(
    chart: InteractiveUiChartModel,
    showData: Boolean,
    enabled: Boolean,
    onToggleData: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val kind = stringResource(
        when (chart) {
        is InteractiveUiChartModel.Categorical -> if (chart.line) R.string.screen_interactive_ui_chart_line else R.string.screen_interactive_ui_chart_bar
        is InteractiveUiChartModel.Pie -> R.string.screen_interactive_ui_chart_pie
        is InteractiveUiChartModel.Scatter -> R.string.screen_interactive_ui_chart_scatter
    }
    )
    val title = chart.title ?: kind
    val category = stringResource(R.string.screen_interactive_ui_category)
    val value = chart.unit ?: stringResource(R.string.screen_interactive_ui_value)
    val xLabel = when (chart) {
        is InteractiveUiChartModel.Categorical -> chart.xLabel ?: category
        is InteractiveUiChartModel.Scatter -> chart.xLabel ?: stringResource(R.string.screen_interactive_ui_x_axis)
        is InteractiveUiChartModel.Pie -> category
    }
    val yLabel = when (chart) {
        is InteractiveUiChartModel.Categorical -> chart.yLabel ?: value
        is InteractiveUiChartModel.Scatter -> chart.yLabel ?: stringResource(R.string.screen_interactive_ui_y_axis)
        is InteractiveUiChartModel.Pie -> value
    }
    val columns = when (chart) {
        is InteractiveUiChartModel.Categorical -> listOf(InteractiveUiTableColumn("category", xLabel, false, false)) +
            chart.series.mapIndexed { index, series -> InteractiveUiTableColumn("series$index", series.name, true, true) }
        is InteractiveUiChartModel.Pie -> listOf(
            InteractiveUiTableColumn("category", category, false, false),
            InteractiveUiTableColumn("value", value, true, true)
        )
        is InteractiveUiChartModel.Scatter -> listOf(
            InteractiveUiTableColumn("series", stringResource(R.string.screen_interactive_ui_series), false, false),
            InteractiveUiTableColumn("label", category, false, false),
            InteractiveUiTableColumn("x", xLabel, true, true),
            InteractiveUiTableColumn("y", yLabel, true, true),
        )
    }.toImmutableList()
    val rows = when (chart) {
        is InteractiveUiChartModel.Categorical -> chart.categories.mapIndexed { index, label ->
            (listOf(InteractiveUiCell.Text(label)) + chart.series.map { series ->
                series.values.getOrNull(index)?.let { InteractiveUiCell.Number(it) } ?: InteractiveUiCell.Empty
            }).toImmutableList()
        }
        is InteractiveUiChartModel.Pie -> chart.categories.mapIndexed { index, label ->
            listOf(InteractiveUiCell.Text(label), chart.values.getOrNull(index)?.let { InteractiveUiCell.Number(it) } ?: InteractiveUiCell.Empty)
                .toImmutableList()
        }
        is InteractiveUiChartModel.Scatter -> chart.series.flatMap { series ->
            series.points.map { (x, y, label) ->
            listOf(
                InteractiveUiCell.Text(series.name),
                label?.let { InteractiveUiCell.Text(it) } ?: InteractiveUiCell.Empty,
                InteractiveUiCell.Number(x),
                InteractiveUiCell.Number(y)
            ).toImmutableList()
        }
        }
    }.toImmutableList()
    val explanation = if (chart is InteractiveUiChartModel.Pie) {
        when {
        chart.values.any { it < 0 } -> stringResource(R.string.screen_interactive_ui_pie_negative_values)
        chart.values.all { it == 0.0 } -> stringResource(R.string.screen_interactive_ui_pie_zero_total)
        else -> null
    }
    } else {
        null
    }
    val summary = pluralStringResource(R.plurals.screen_interactive_ui_chart_summary, rows.size, title, kind, rows.size)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.semantics { heading() }, style = ElementTheme.typography.fontHeadingSmMedium)
        Text(summary)
        if (explanation != null) {
            Text(explanation)
        } else {
            Text(
            stringResource(R.string.screen_interactive_ui_data_table),
            Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { selected = showData }
                .clickable(enabled = enabled, role = Role.Button) { onToggleData(!showData) }
                .padding(8.dp),
        )
        }
        if (showData || explanation != null) {
            InteractiveUiTableView(title + chart.unit?.let { " ($it)" }.orEmpty(), columns, rows, null, false, enabled, {})
        } else {
            ChartDrawing(chart, summary, xLabel, yLabel)
            Text(stringResource(R.string.screen_interactive_ui_legend), Modifier.semantics { heading() })
            when (chart) {
                is InteractiveUiChartModel.Categorical -> chart.series.forEachIndexed { index, series ->
                    Text("${seriesMarker(index)} ${series.name}: " + chart.categories.mapIndexed { i, label ->
                        "$label ${series.values.getOrNull(i)?.let(::displayNumber).orEmpty()}"
                    }.joinToString("; ") + chart.unit?.let { " $it" }.orEmpty())
                }
                is InteractiveUiChartModel.Pie -> chart.categories.forEachIndexed { index, label ->
                    Text("${index + 1}. $label: ${chart.values.getOrNull(index)?.let(::displayNumber).orEmpty()}" + chart.unit?.let { " $it" }.orEmpty())
                }
                is InteractiveUiChartModel.Scatter -> chart.series.forEachIndexed { index, series ->
                    Text("${seriesMarker(index)} ${series.name}: " + series.points.joinToString("; ") { (x, y, label) ->
                        "${label?.let { "$it: " }.orEmpty()}(${displayNumber(x)}, ${displayNumber(y)})"
                    } + chart.unit?.let { " $it" }.orEmpty())
                }
            }
        }
    }
}
