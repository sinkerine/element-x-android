/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.libraries.interactiveui.ui.chart

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.interactiveui.ui.table.displayNumber
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.setSafeContent
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InteractiveUiChartViewTest : RobolectricTest() {
    @Test
    fun `multiline titles and long categories fit measured chart at normal font`() = assertLabelBounds(1f, false)

    @Test
    fun `multiline titles and long categories fit measured chart at two hundred percent font`() = assertLabelBounds(2f, false)

    @Test
    fun `long scatter endpoint ticks fit scroll bounds at normal font`() = assertLabelBounds(1f, true)

    @Test
    fun `long scatter endpoint ticks fit scroll bounds at two hundred percent font`() = assertLabelBounds(2f, true)

    @Test
    fun `scatter adjacent tick rectangles never intersect at normal font`() = assertAdjacentLabels(1f, true)

    @Test
    fun `scatter adjacent tick rectangles never intersect at two hundred percent font`() = assertAdjacentLabels(2f, true)

    @Test
    fun `six long category rectangles never intersect at normal font`() = assertAdjacentLabels(1f, false)

    @Test
    fun `six long category rectangles never intersect at two hundred percent font`() = assertAdjacentLabels(2f, false)

    @Test
    @Config(qualifiers = "h2000dp")
    fun `RTL category labels align with Canvas bars and grid at normal font`() = assertRtlCanvasAlignment(1f, false)

    @Test
    @Config(qualifiers = "h2000dp")
    fun `RTL category labels align with Canvas bars and grid at two hundred percent font`() = assertRtlCanvasAlignment(2f, false)

    @Test
    @Config(qualifiers = "h2000dp")
    fun `RTL scatter ticks align with Canvas markers and grid at normal font`() = assertRtlCanvasAlignment(1f, true)

    @Test
    @Config(qualifiers = "h2000dp")
    fun `RTL scatter ticks align with Canvas markers and grid at two hundred percent font`() = assertRtlCanvasAlignment(2f, true)

    private fun assertRtlCanvasAlignment(fontScale: Float, scatter: Boolean) = runAndroidComposeUiTest {
        val categories = listOf("بداية", "وسط", "نهاية")
        val chart = if (scatter) {
            InteractiveUiChartModel.Scatter(
                "Chart",
                null,
                "المحور الأفقي",
                "المحور الرأسي",
                persistentListOf(InteractiveUiChartModel.ScatterSeries(
                    "Series",
                    persistentListOf(Triple(0.0, 4.0, null), Triple(5.0, 4.0, null), Triple(10.0, 4.0, null)),
                )),
            )
        } else {
            InteractiveUiChartModel.Categorical(
                "Chart",
                null,
                false,
                "المحور الأفقي",
                "المحور الرأسي",
                categories.toImmutableList(),
                persistentListOf(InteractiveUiChartModel.Series("Series", persistentListOf(2.0, 6.0, 10.0))),
            )
        }
        var pixelsPerDp = 1f
        var dataColor = Color.Unspecified
        var gridColor = Color.Unspecified
        setSafeContent {
            ElementPreview {
                pixelsPerDp = LocalDensity.current.density
                dataColor = if (scatter) ElementTheme.colors.textDecorative1 else ElementTheme.colors.bgDecorative1
                gridColor = ElementTheme.colors.borderInteractiveSecondary
                CompositionLocalProvider(
                    LocalDensity provides Density(pixelsPerDp, fontScale),
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                ) {
                    Column(Modifier.width(240.dp).verticalScroll(rememberScrollState())) {
                        ChartDrawing(chart, "Drawing", "المحور الأفقي", "المحور الرأسي")
                    }
                }
            }
        }
        val scroll = onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
        fun label(text: String, bottommost: Boolean = false) = onAllNodesWithText(text, useUnmergedTree = true).let { matches ->
            matches[matches.fetchSemanticsNodes().indices.let { indices ->
                if (bottommost) indices.maxBy { matches[it].getUnclippedBoundsInRoot().top.value } else indices.first()
            }]
        }
        fun centerY(text: String): Float {
            val bounds = label(text).getUnclippedBoundsInRoot()
            return ((bounds.top + bounds.bottom) / 2).value
        }
        fun assertPixels(endpoint: Int) {
            val tickText = if (scatter) (if (endpoint == 0) "0" else "10") else categories[endpoint]
            val before = label(tickText, bottommost = true).getUnclippedBoundsInRoot()
            val frame = scroll.getUnclippedBoundsInRoot()
            val shift = ((frame.left + frame.right - before.left - before.right) / 2).value * pixelsPerDp
            scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(shift, 0f) }
            waitForIdle()
            val content = onNodeWithContentDescription("Drawing").getUnclippedBoundsInRoot()
            val viewport = scroll.getUnclippedBoundsInRoot()
            val image = scroll.captureToImage().toPixelMap()
            fun colorAt(x: Float, y: Float): Color = image[
                ((x - viewport.left.value) * pixelsPerDp).toInt().coerceIn(0, image.width - 1),
                ((y - viewport.top.value) * pixelsPerDp).toInt().coerceIn(0, image.height - 1),
            ]
            fun nearColor(x: Float, y: Float, color: Color): Boolean = (-2..2).any { dx ->
                (-2..2).any { dy -> colorAt(x + dx / pixelsPerDp, y + dy / pixelsPerDp) == color }
            }
            val labels = onAllNodes(hasTestTag("interactive-ui-chart-label"), useUnmergedTree = true)
            labels.fetchSemanticsNodes().indices.forEach { index ->
                val bounds = labels[index].getUnclippedBoundsInRoot()
                assertThat(bounds.left.value).isAtLeast(content.left.value - 1f)
                assertThat(bounds.right.value).isAtMost(content.right.value + 1f)
                assertThat(bounds.bottom.value).isAtMost(content.bottom.value + 1f)
                val results = mutableListOf<TextLayoutResult>()
                labels[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                assertThat(results.single().layoutInput.layoutDirection).isEqualTo(LayoutDirection.Rtl)
                assertThat(results.single().hasVisualOverflow).isFalse()
            }
            val xTick = label(tickText, bottommost = true).getUnclippedBoundsInRoot()
            val x = ((xTick.left + xTick.right) / 2).value
            assertThat(x).isAtLeast(viewport.left.value)
            assertThat(x).isAtMost(viewport.right.value)
            val zeroY = centerY("0")
            val maxY = centerY(if (scatter) "4" else "10")
            // Read the actual Canvas pixels at the label's x coordinate and the independently interpolated data y coordinate.
            val value = listOf(2f, 6f, 10f)[endpoint]
            val dataY = if (scatter) maxY else zeroY + (maxY - zeroY) * value / 20f
            assertThat(nearColor(x, dataY, dataColor)).isTrue()
            if (!scatter && endpoint == 0) {
                assertThat(nearColor(x, zeroY + (maxY - zeroY) * 0.3f, dataColor)).isFalse()
            }
            val gridY = centerY(if (scatter) "2" else "4")
            val gapX = if (scatter) {
                x + (if (endpoint == 0) 24 else -24) * fontScale
            } else {
                val categoryWidth = (content.right.value - content.left.value - 16 * fontScale -
                    (onNodeWithText("المحور الرأسي", useUnmergedTree = true).getUnclippedBoundsInRoot().left - content.left).value) / 3
                x + categoryWidth * 0.4f
            }
            // A one-pixel grid line can straddle rows. Sum its coverage rather than demanding an opaque pixel.
            val background = colorAt(gapX, gridY - 4 / pixelsPerDp)
            val samples = (-2..2).map { colorAt(gapX, gridY + it / pixelsPerDp) }
            assertThat(samples.sumOf { (background.red - it.red).toDouble() }.toFloat())
                .isWithin(0.03f)
                .of(background.red - gridColor.red)
            assertThat(samples.sumOf { (background.green - it.green).toDouble() }.toFloat())
                .isWithin(0.03f)
                .of(background.green - gridColor.green)
            assertThat(samples.sumOf { (background.blue - it.blue).toDouble() }.toFloat())
                .isWithin(0.03f)
                .of(background.blue - gridColor.blue)
        }
        // RTL starts at the physical right end; positive ScrollBy reaches the physical left end.
        assertPixels(2)
        scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(Float.MAX_VALUE, 0f) }
        waitForIdle()
        assertPixels(0)
    }

    private fun assertAdjacentLabels(fontScale: Float, scatter: Boolean) = runAndroidComposeUiTest {
        val ranges = if (scatter) {
            listOf(
                -1e20 to 1e20,
                0.0 to 0.0,
                7.0 to 7.0,
                -7.0 to -7.0,
                -Double.MAX_VALUE to Double.MAX_VALUE,
                -Double.MIN_VALUE to Double.MIN_VALUE,
                0.0 to Double.MIN_VALUE,
                Double.MIN_VALUE to 8 * Double.MIN_VALUE,
            )
        } else {
            listOf(-1e20 to 1e20)
        }
        val categories = List(6) { "Category $it with a long label which wraps across multiple lines" }
        fun chart(range: Pair<Double, Double>): InteractiveUiChartModel = if (scatter) {
            InteractiveUiChartModel.Scatter(
                "Chart",
                null,
                "Horizontal axis",
                "Vertical axis",
                persistentListOf(
                    InteractiveUiChartModel.ScatterSeries(
                        "Series",
                        persistentListOf(Triple(range.first, range.first, null), Triple(range.second, range.second, null))
                    )
                )
            )
        } else {
            InteractiveUiChartModel.Categorical(
                "Chart",
                null,
                false,
                "Horizontal axis",
                "Vertical axis",
                categories.toImmutableList(),
                persistentListOf(InteractiveUiChartModel.Series("Series", List(6) { if (it % 2 == 0) range.first else range.second }.toImmutableList()))
            )
        }
        val currentChart = mutableStateOf(chart(ranges.first()))
        setSafeContent {
            ElementPreview {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Column(Modifier.width(240.dp).verticalScroll(rememberScrollState())) {
                        ChartDrawing(currentChart.value, "Drawing", "Horizontal axis", "Vertical axis")
                    }
                }
            }
        }
        val scroll = onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
        for (range in ranges) {
            runOnIdle { currentChart.value = chart(range) }
            scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(-Float.MAX_VALUE, 0f) }
            waitForIdle()
            val tickTexts = if (scatter) ChartAxis(listOf(range.first, range.second)).ticks.map(::displayNumber) else categories
            val labels = tickTexts.map { text ->
                val matches = onAllNodesWithText(text, useUnmergedTree = true)
                matches[matches.fetchSemanticsNodes().indices.maxBy { matches[it].getUnclippedBoundsInRoot().top.value }]
            }
            fun assertRectangles() {
                val content = onNodeWithContentDescription("Drawing").getUnclippedBoundsInRoot()
                assertThat((content.right - content.left).value.isFinite()).isTrue()
                assertThat((content.right - content.left).value).isLessThan(10_000f * fontScale)
                val rectangles = labels.map { label ->
                    val results = mutableListOf<TextLayoutResult>()
                    label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                    assertThat(results.single().hasVisualOverflow).isFalse()
                    label.getUnclippedBoundsInRoot().also { bounds ->
                        assertThat(bounds.left.value).isAtLeast(content.left.value)
                        assertThat(bounds.right.value).isAtMost(content.right.value)
                        assertThat(bounds.bottom.value).isAtMost(content.bottom.value)
                    }
                }
                rectangles.zipWithNext { first, second ->
                    assertThat(second.top).isEqualTo(first.top)
                    assertThat((second.left - first.right).value).isAtLeast(1f)
                }
            }
            assertRectangles()
            scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(Float.MAX_VALUE, 0f) }
            waitForIdle()
            assertRectangles()
            val viewport = scroll.getUnclippedBoundsInRoot()
            val last = labels.last().getUnclippedBoundsInRoot()
            assertThat(last.right.value).isAtMost(viewport.right.value)
        }
    }

    private fun assertLabelBounds(fontScale: Float, scatter: Boolean) = runAndroidComposeUiTest {
        val xTitle = "First\nSecond\nThird"
        val yTitle = "Vertical title\nSecond title line\nThird title line"
        val unit = "a long unit which expands the measured vertical title ".repeat(4)
        val category = "A category with many words which wrap across multiple lines ".repeat(3)
        val chart = if (scatter) {
            InteractiveUiChartModel.Scatter(
                "Chart",
                unit,
                xTitle,
                yTitle,
                persistentListOf(
                    InteractiveUiChartModel.ScatterSeries(
                        "Series",
                        persistentListOf(Triple(-1_000_000.0, -1_000_000.0, null), Triple(1_000_000.0, 1_000_000.0, null))
                    )
                )
            )
        } else {
            InteractiveUiChartModel.Categorical(
                "Chart",
                unit,
                false,
                xTitle,
                yTitle,
                listOf(category, "Last category").toImmutableList(),
                persistentListOf(InteractiveUiChartModel.Series("Series", persistentListOf(-1_000_000.0, 1_000_000.0))),
            )
        }
        setSafeContent {
            ElementPreview {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Column(Modifier.width(240.dp).verticalScroll(rememberScrollState())) {
                        ChartDrawing(chart, "Drawing", xTitle, yTitle)
                    }
                }
            }
        }
        val drawing = onNodeWithContentDescription("Drawing")
        val canvas = drawing.getUnclippedBoundsInRoot()
        val labels = onAllNodes(hasTestTag("interactive-ui-chart-label"), useUnmergedTree = true)
        val nodes = labels.fetchSemanticsNodes()
        assertThat(nodes.size).isAtLeast(9)
        for (index in nodes.indices) {
            val bounds = labels[index].getUnclippedBoundsInRoot()
            assertThat(bounds.left.value).isAtLeast(canvas.left.value - 1f)
            assertThat(bounds.right.value).isAtMost(canvas.right.value + 1f)
            assertThat(bounds.top.value).isAtLeast(canvas.top.value - 1f)
            assertThat(bounds.bottom.value).isAtMost(canvas.bottom.value + 1f)
            val results = mutableListOf<TextLayoutResult>()
            labels[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertThat(results.single().hasVisualOverflow).isFalse()
        }
        val bottomTitle = onNodeWithText(xTitle, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val topTitle = onNodeWithText("$yTitle ($unit)", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertThat((bottomTitle.bottom - bottomTitle.top).value).isGreaterThan(40f * fontScale)
        assertThat(topTitle.bottom.value).isLessThan(bottomTitle.top.value)
        val tickText = if (scatter) "1000000" else "Last category"
        val endpoints = onAllNodesWithText(tickText, useUnmergedTree = true)
        val endpointIndex = endpoints.fetchSemanticsNodes().indices.maxBy { endpoints[it].getUnclippedBoundsInRoot().top.value }
        val tick = endpoints[endpointIndex].getUnclippedBoundsInRoot()
        assertThat(tick.bottom.value).isLessThan(bottomTitle.top.value)
        val yTick = onAllNodesWithText("-1000000", useUnmergedTree = true)[0].getUnclippedBoundsInRoot()
        assertThat(yTick.bottom.value).isLessThan(tick.top.value)
        val scroll = onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
        scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(Float.MAX_VALUE, 0f) }
        waitForIdle()
        val viewport = scroll.getUnclippedBoundsInRoot()
        val endpoint = endpoints[endpointIndex].getUnclippedBoundsInRoot()
        assertThat(endpoint.right.value).isAtMost(viewport.right.value + 1f)
        if (scatter) assertThat(endpoint.left.value).isAtLeast(viewport.left.value - 1f)
    }

    @Test
    fun `bar fixture exposes summary visible data axis labels and equivalent table`() = runAndroidComposeUiTest {
        val chart = InteractiveUiChartModelPreviewParam().values.first()
        val data = mutableStateOf(false)
        setSafeContent { ElementPreview { InteractiveUiChartView(chart, data.value, true, { data.value = it }) } }
        onNodeWithContentDescription("Example data. Bar chart with 2 data rows.").assertExists()
        onNodeWithText("● Mass: A 2; B 3 kg").assertExists()
        onNodeWithText("■ Target: A -1; B 4 kg").assertExists()
        onNodeWithText("Data table").performClick()
        onNodeWithText("Example data (kg)").assertExists()
        onNodeWithText("Mass").assertExists()
        onNodeWithText("2").assertExists()
        onNodeWithText("-1").assertExists()
        onNodeWithText("Data table").performClick()
        onNodeWithContentDescription("Example data. Bar chart with 2 data rows.").assertExists()
    }

    @Test
    fun `line and scatter fixture models draw series and retain every labelled point`() = runAndroidComposeUiTest {
        val chart = mutableStateOf(InteractiveUiChartModelPreviewParam().values.elementAt(1))
        setSafeContent { ElementPreview { InteractiveUiChartView(chart.value, false, true, {}) } }
        onNodeWithContentDescription("Example data. Line chart with 2 data rows.").assertExists()
        onNodeWithText("■ Target: A 1; B 4 kg").assertExists()
        runOnIdle { chart.value = InteractiveUiChartModelPreviewParam().values.elementAt(2) }
        onNodeWithContentDescription("Example data. Scatter chart with 2 data rows.").assertExists()
        onNodeWithText("● Mass: A: (1, 2); (2, 3) kg").assertExists()
    }

    @Test
    fun `single positive pie renders and negative or zero pies show explanations and all values`() = runAndroidComposeUiTest {
        val chart = mutableStateOf<InteractiveUiChartModel>(InteractiveUiChartModel.Pie("Pie", "kg", persistentListOf("A"), persistentListOf(2.0)))
        setSafeContent { ElementPreview { InteractiveUiChartView(chart.value, false, true, {}) } }
        onNodeWithContentDescription("Pie. Pie chart with 1 data rows.").assertExists()
        onNodeWithText("1. A: 2 kg").assertExists()
        runOnIdle { chart.value = InteractiveUiChartModel.Pie("Pie", "kg", persistentListOf("A", "B"), persistentListOf(-2.0, 3.0)) }
        onNodeWithText("A pie chart cannot display negative values. All values are shown in the data table.").assertExists()
        onNodeWithText("-2").assertExists()
        onNodeWithText("3").assertExists()
        runOnIdle { chart.value = InteractiveUiChartModel.Pie("Pie", null, persistentListOf("A"), persistentListOf(0.0)) }
        onNodeWithText("The pie chart total is zero. All values are shown in the data table.").assertExists()
        onNodeWithText("0").assertExists()
    }

    @Test
    fun `large font long categories stay in horizontally scrollable chart`() = runAndroidComposeUiTest {
        val chart = InteractiveUiChartModel.Categorical(
            "Chart title",
            "kg",
            true,
            "Horizontal category axis with wrapping labels",
            "Vertical mass axis with wrapping labels",
            persistentListOf("A long category label which should wrap over multiple lines", "B"),
            persistentListOf(InteractiveUiChartModel.Series("Mass", persistentListOf(-1e308, 1e308)))
        )
        setSafeContent {
            ElementPreview {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                InteractiveUiChartView(chart, false, true, {}, Modifier.width(360.dp))
            }
        }
        }
        val range = onNode(hasScrollAction()).fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertThat(range.maxValue()).isGreaterThan(0f)
        onNodeWithText("● Mass: A long category label which should wrap over multiple lines -1e+308; B 1e+308 kg").assertExists()
    }
}
