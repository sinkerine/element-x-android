/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.swipe.SwipeSensitivity
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.utils.BlockAppDrawerGestures
import io.element.android.libraries.interactiveui.ui.table.displayNumber
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Ported from element-web packages/shared-components/src/room/timeline/event-tile/body/InteractiveUiView/chart/InteractiveUiChartView.tsx
 * at bcb08017 (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial).
 */
internal fun seriesMarker(index: Int): String = listOf("●", "■", "▲", "◆", "+", "×", "○", "□")[index % 8]
internal fun seriesDash(index: Int): List<Float> = listOf(
    emptyList(),
    listOf(8f, 4f),
    listOf(2f, 4f),
    listOf(10f, 3f, 2f, 3f),
    listOf(14f, 4f),
    listOf(4f, 4f),
    listOf(10f, 3f, 2f, 3f, 2f, 3f),
    listOf(2f, 2f),
)[index % 8]

@Composable
internal fun ChartDrawing(chart: InteractiveUiChartModel, summary: String, xLabel: String, yLabel: String) {
    val textMeasurer = rememberTextMeasurer()
    val colors = ElementTheme.colors
    val palette = listOf(
        colors.bgDecorative1,
        colors.bgDecorative2,
        colors.bgDecorative3,
        colors.bgDecorative4,
        colors.bgDecorative5,
        colors.bgDecorative6
    )
    val foreground = listOf(
        colors.textDecorative1,
        colors.textDecorative2,
        colors.textDecorative3,
        colors.textDecorative4,
        colors.textDecorative5,
        colors.textDecorative6
    )
    val style = ElementTheme.typography.fontBodySmRegular.copy(color = colors.textPrimary)
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val density = LocalDensity.current
    val categories = when (chart) {
        is InteractiveUiChartModel.Categorical -> chart.categories
        else -> emptyList()
    }
    val unit = density.density * scale
    val padding = 16 * unit
    val gap = 8 * unit
    val baseWidth = maxOf(640f, 184f + categories.size * 100f) * unit
    fun measure(text: String, maxWidth: Float = baseWidth - 2 * padding) =
        textMeasurer.measure(text, style, constraints = Constraints(maxWidth = maxWidth.toInt()))
    val yValues = when (chart) {
        is InteractiveUiChartModel.Categorical -> chart.series.flatMap { it.values }
        is InteractiveUiChartModel.Scatter -> chart.series.flatMap { series -> series.points.map { it.second } }
        is InteractiveUiChartModel.Pie -> emptyList()
    }
    val yAxis = ChartAxis(yValues)
    val xAxis = if (chart is InteractiveUiChartModel.Scatter) ChartAxis(chart.series.flatMap { series -> series.points.map { it.first } }) else null
    val yTicks = yAxis.ticks.map { measure(displayNumber(it)) }
    val xTicks = xAxis?.ticks?.map { measure(displayNumber(it)) }.orEmpty()
    val categoryLabels = categories.map { measure(it, 96 * unit) }
    val xTickMargin = xTicks.maxOfOrNull { it.size.width / 2f } ?: 0f
    val left = padding + maxOf((yTicks.maxOfOrNull { it.size.width } ?: 0) + gap, xTickMargin)
    // Reserve both endpoint margins as well as enough plot width for every adjacent pair of ticks.
    val rightMargin = padding + xTickMargin
    val width = maxOf(baseWidth, left + (xAxis?.minimumPlotWidth(xTicks.map { it.size.width }, gap) ?: 0f) + rightMargin)
    val right = width - rightMargin
    val plotWidth = right - left
    val yTitle = measure(yLabel + chart.unit?.takeUnless { it == yLabel }?.let { " ($it)" }.orEmpty(), plotWidth)
    val xTitle = measure(xLabel, plotWidth)
    val tickHalfHeight = yTicks.maxOfOrNull { it.size.height / 2f } ?: 0f
    val top = padding + yTitle.size.height + gap + tickHalfHeight
    val bottom = top + 252 * unit
    val xLabelsTop = bottom + tickHalfHeight + gap
    val xLabelsHeight = (categoryLabels + xTicks).maxOfOrNull { it.size.height } ?: 0
    val xTitleTop = xLabelsTop + xLabelsHeight + gap
    val height = if (chart is InteractiveUiChartModel.Pie) 360 * unit else xTitleTop + xTitle.size.height + padding
    fun y(value: Double) = bottom - yAxis.position(value) * (bottom - top)
    val labels = buildList {
        if (chart !is InteractiveUiChartModel.Pie) {
            add(yTitle to Offset(left, padding))
            add(xTitle to Offset(left + (plotWidth - xTitle.size.width) / 2, xTitleTop))
            yTicks.forEachIndexed { index, text -> add(text to Offset(padding, y(yAxis.ticks[index]) - text.size.height / 2f)) }
            categoryLabels.forEachIndexed { index, text ->
                val center = left + (index + 0.5f) * plotWidth / categories.size
                add(text to Offset(center - text.size.width / 2f, xLabelsTop))
            }
            xTicks.forEachIndexed { index, text ->
                val center = left + xAxis!!.position(xAxis.ticks[index]) * plotWidth
                add(text to Offset((center - text.size.width / 2f).coerceIn(padding, width - padding - text.size.width), xLabelsTop))
            }
        }
    }
    val scroll = rememberScrollState()
    if (scroll.isScrollInProgress) BlockAppDrawerGestures()
    SwipeSensitivity(1f / 3f) {
        Box(Modifier.horizontalScroll(scroll)) {
            // Canvas and overlays share physical coordinates; text still inherits the surrounding layout direction.
            Box(
                Modifier.size(with(density) { width.toDp() }, with(density) { height.toDp() }).semantics { contentDescription = summary },
                contentAlignment = AbsoluteAlignment.TopLeft,
            ) {
                Canvas(Modifier.matchParentSize()) {
                    fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
                    if (chart is InteractiveUiChartModel.Pie) {
                        val maximum = chart.values.maxOrNull() ?: 1.0
                        val values = chart.values.map { it / maximum }
                        val total = values.sum()
                        var angle = -90f
                        values.forEachIndexed { index, value ->
                            val sweep = (value / total * 360).toFloat()
                            val center = point(320f, 166f)
                            drawArc(palette[index % 6], angle, sweep, true, center - point(116f, 116f), Size(232 * unit, 232 * unit))
                            drawArc(
                                colors.textPrimary,
                                angle,
                                sweep,
                                true,
                                center - point(116f, 116f),
                                Size(232 * unit, 232 * unit),
                                style = Stroke(1.dp.toPx())
                            )
                            if (sweep > 0f) {
                                val radians = Math.toRadians((angle + sweep / 2).toDouble())
                                drawText(measure("${index + 1}"), topLeft = center + point((cos(radians) * 80).toFloat(), (sin(radians) * 80).toFloat()))
                            }
                            angle += sweep
                        }
                    } else {
                        yAxis.ticks.forEach { tick ->
                            drawLine(colors.borderInteractiveSecondary, Offset(left, y(tick)), Offset(right, y(tick)))
                        }
                        if (chart is InteractiveUiChartModel.Categorical) {
                            val categoryWidth = (right - left) / maxOf(1, chart.categories.size)
                            chart.series.forEachIndexed { seriesIndex, series ->
                                val color = foreground[seriesIndex % 6]
                                val points = series.values.mapIndexed { index, value -> Offset(left + (index + 0.5f) * categoryWidth, y(value)) }
                                if (chart.line) {
                                    val effect = seriesDash(seriesIndex).takeIf { it.isNotEmpty() }?.map { it * unit }?.toFloatArray()
                                        ?.let { PathEffect.dashPathEffect(it) }
                                    points.zipWithNext { a, b -> drawLine(color, a, b, 2 * unit, pathEffect = effect) }
                                    points.forEach { drawMarker(seriesIndex, it, color, textMeasurer, style) }
                                } else {
                                    val barWidth = categoryWidth / maxOf(1, chart.series.size) * 0.8f
                                    series.values.forEachIndexed { index, value ->
                                        val x = left + index * categoryWidth + seriesIndex * categoryWidth / chart.series.size
                                        val zero = y(0.0)
                                        val yy = y(value)
                                        drawRect(palette[seriesIndex % 6], Offset(x, minOf(yy, zero)), Size(barWidth, abs(yy - zero)))
                                        drawMarker(seriesIndex, Offset(x + barWidth / 2, (yy + zero) / 2), color, textMeasurer, style)
                                    }
                                }
                            }
                        } else if (chart is InteractiveUiChartModel.Scatter) {
                            chart.series.forEachIndexed { index, series ->
                                series.points.forEach { (x, yy, _) ->
                                    drawMarker(
                                        index,
                                        Offset(left + xAxis!!.position(x) * (right - left), y(yy)),
                                        foreground[index % 6],
                                        textMeasurer,
                                        style
                                    )
                                }
                            }
                        }
                    }
                }
                labels.forEach { (text, position) ->
                    ChartLabel(text, position, style)
                }
            }
        }
    }
}

@Composable
private fun ChartLabel(text: TextLayoutResult, position: Offset, style: TextStyle) {
    val density = LocalDensity.current
    Text(
        text.layoutInput.text,
        Modifier.absoluteOffset(with(density) { position.x.toDp() }, with(density) { position.y.toDp() })
            .width(with(density) { text.size.width.toDp() })
            .testTag("interactive-ui-chart-label"),
        style = style,
    )
}

private fun DrawScope.drawMarker(
    index: Int, point: Offset, color: Color, measurer: TextMeasurer, style: TextStyle,
) {
    val text = measurer.measure(seriesMarker(index), style.copy(color = color))
    drawText(text, topLeft = point - Offset(text.size.width / 2f, text.size.height / 2f))
}
