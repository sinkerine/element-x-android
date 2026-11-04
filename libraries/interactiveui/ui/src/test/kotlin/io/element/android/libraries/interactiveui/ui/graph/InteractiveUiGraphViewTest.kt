/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.libraries.interactiveui.ui.graph

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.setSafeContent
import org.junit.Test
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InteractiveUiGraphViewTest : RobolectricTest() {
    @Test
    fun `diagram node bounds attach to routes at two hundred percent font`() = assertDiagramBounds(2f, "Short relationship")

    @Test
    fun `long relationship scales node height together with route anchors`() = assertDiagramBounds(
        1f,
        "A long relationship label with many words which must wrap across several lines without detaching the route from its node",
    )

    private fun assertDiagramBounds(fontScale: Float, relationship: String) = runAndroidComposeUiTest {
        val input = graphFixtures("knowledge-map.en").first()
        val layout = layoutLayeredGraph(input.copy(
            topToBottom = true,
            nodes = input.nodes.mapIndexed { index, node -> node.copy(label = if (index == 1) "A long node label with several wrapped words" else node.label) },
            edges = input.edges.map { it.copy(label = relationship) },
        ))
        val geometry = graphGeometry(layout)
        setSafeContent {
            ElementPreview {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        InteractiveUiGraphView(layout, null, false, true, {}, {}, Modifier.width(360.dp))
                    }
                }
            }
        }
        val diagram = onNodeWithTag("interactive-ui-graph-diagram").getUnclippedBoundsInRoot()
        val scale = (diagram.right - diagram.left).value / geometry.width
        assertThat(scale).isGreaterThan(1f)
        layout.edges.forEachIndexed { index, edge ->
            val route = geometry.paths[index]
            val fromNode = layout.nodes.single { it.id == edge.from }
            val from = onNodeWithContentDescription("${fromNode.label}. ${fromNode.detail.orEmpty()}").getUnclippedBoundsInRoot()
            val toNode = layout.nodes.single { it.id == edge.to }
            val to = onNodeWithContentDescription("${toNode.label}. ${toNode.detail.orEmpty()}").getUnclippedBoundsInRoot()
            assertThat(from.bottom.value).isWithin(1f).of(diagram.top.value + route.segments.first().start.y * scale)
            assertThat(((from.left + from.right) / 2).value).isWithin(1f).of(diagram.left.value + route.segments.first().start.x * scale)
            assertThat(to.top.value).isWithin(1f).of(diagram.top.value + route.segments.last().end.y * scale)
            assertThat(((to.left + to.right) / 2).value).isWithin(1f).of(diagram.left.value + route.segments.last().end.x * scale)
        }
        layout.nodes.forEach { node ->
            val bounds = onNodeWithContentDescription("${node.label}. ${node.detail.orEmpty()}").getUnclippedBoundsInRoot()
            val box = geometry.boxes.getValue(node.id)
            assertThat((bounds.right - bounds.left).value).isWithin(1f).of(box.width * scale)
            assertThat((bounds.bottom - bounds.top).value).isWithin(1f).of(box.height * scale)
            val results = mutableListOf<TextLayoutResult>()
            onNodeWithText(node.label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertThat(results.single().hasVisualOverflow).isFalse()
        }
        val edgeLabels = onAllNodesWithText(relationship)
        edgeLabels.fetchSemanticsNodes().indices.forEach { index ->
            val results = mutableListOf<TextLayoutResult>()
            edgeLabels[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertThat(results.single().hasVisualOverflow).isFalse()
            val bounds = edgeLabels[index].getUnclippedBoundsInRoot()
            assertThat((bounds.bottom - bounds.top).value).isAtMost(geometry.paths[index].labelBox.height * scale + 1f)
        }
    }

    @Test
    fun `asymmetric horizontal RTL graph attaches overlays to routes at normal font`() = assertRtlDiagramBounds(false, 1f)

    @Test
    fun `asymmetric horizontal RTL graph attaches overlays to routes at two hundred percent font`() = assertRtlDiagramBounds(false, 2f)

    @Test
    fun `asymmetric vertical RTL graph attaches overlays to routes at normal font`() = assertRtlDiagramBounds(true, 1f)

    @Test
    fun `asymmetric vertical RTL graph attaches overlays to routes at two hundred percent font`() = assertRtlDiagramBounds(true, 2f)

    private fun assertRtlDiagramBounds(topToBottom: Boolean, fontScale: Float) = runAndroidComposeUiTest {
        val input = graphFixtures("six-steps").single()
        val layout = layoutLayeredGraph(input.copy(
            topToBottom = topToBottom,
            layers = input.layers.indices.map { "Layer $it" },
            nodes = input.nodes.mapIndexed { index, node -> node.copy(label = "مرحلة $index") } + input.nodes.first().copy(
                id = "extra",
                label = "فرع إضافي",
                layer = 0,
            ),
            edges = input.edges.mapIndexed { index, edge -> edge.copy(label = "صلة $index") },
        ))
        val geometry = graphGeometry(layout)
        setSafeContent {
            ElementPreview {
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, fontScale),
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        InteractiveUiGraphView(layout, null, false, true, {}, {}, Modifier.width(360.dp))
                    }
                }
            }
        }
        val scroll = onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
        fun assertCoordinates() {
            val diagram = onNodeWithTag("interactive-ui-graph-diagram").getUnclippedBoundsInRoot()
            val scale = (diagram.right - diagram.left).value / geometry.width
            fun x(value: Float) = diagram.left.value + value * scale
            fun y(value: Float) = diagram.top.value + value * scale
            layout.nodes.forEach { node ->
                val bounds = onNodeWithContentDescription("${node.label}. ").getUnclippedBoundsInRoot()
                val box = geometry.boxes.getValue(node.id)
                assertThat(bounds.left.value).isWithin(1f).of(x(box.left))
                assertThat(bounds.top.value).isWithin(1f).of(y(box.top))
                assertThat(bounds.right.value).isWithin(1f).of(x(box.right))
                assertThat(bounds.bottom.value).isWithin(1f).of(y(box.bottom))
                val text = mutableListOf<TextLayoutResult>()
                onNodeWithText(node.label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(text) }
                assertThat(text.single().layoutInput.layoutDirection).isEqualTo(LayoutDirection.Rtl)
                assertThat(text.single().hasVisualOverflow).isFalse()
            }
            layout.edges.forEachIndexed { index, edge ->
                val route = geometry.paths[index]
                val from = onNodeWithContentDescription("${layout.nodes.single { it.id == edge.from }.label}. ").getUnclippedBoundsInRoot()
                val to = onNodeWithContentDescription("${layout.nodes.single { it.id == edge.to }.label}. ").getUnclippedBoundsInRoot()
                val start = route.segments.first().start
                val end = route.segments.last().end
                if (topToBottom) {
                    assertThat(from.bottom.value).isWithin(1f).of(y(start.y))
                    assertThat(((from.left + from.right) / 2).value).isWithin(1f).of(x(start.x))
                    assertThat(to.top.value).isWithin(1f).of(y(end.y))
                    assertThat(((to.left + to.right) / 2).value).isWithin(1f).of(x(end.x))
                } else {
                    assertThat(from.right.value).isWithin(1f).of(x(start.x))
                    assertThat(((from.top + from.bottom) / 2).value).isWithin(1f).of(y(start.y))
                    assertThat(to.left.value).isWithin(1f).of(x(end.x))
                    assertThat(((to.top + to.bottom) / 2).value).isWithin(1f).of(y(end.y))
                }
                val label = onNodeWithText(edge.label!!).getUnclippedBoundsInRoot()
                assertThat(label.left.value).isWithin(1f).of(x(route.labelBox.left))
                assertThat(label.top.value).isWithin(1f).of(y(route.labelBox.top))
                assertThat(label.right.value).isAtMost(diagram.right.value + 1f)
            }
            geometry.headings.forEach { (position, label) ->
                val bounds = onNodeWithText(label).getUnclippedBoundsInRoot()
                assertThat(bounds.left.value).isWithin(1f).of(x(position.x))
                assertThat(bounds.top.value).isWithin(1f).of(y(position.y - 20))
            }
        }
        assertCoordinates()
        scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(Float.MAX_VALUE, 0f) }
        waitForIdle()
        assertCoordinates()
        val diagram = onNodeWithTag("interactive-ui-graph-diagram").getUnclippedBoundsInRoot()
        layout.nodes.forEach { node ->
            val bounds = onNodeWithContentDescription("${node.label}. ").getUnclippedBoundsInRoot()
            assertThat(bounds.left.value).isAtLeast(diagram.left.value - 1f)
            assertThat(bounds.right.value).isAtMost(diagram.right.value + 1f)
            assertThat(bounds.bottom.value).isAtMost(diagram.bottom.value + 1f)
        }
    }

    @Test
    fun `list nodes keep flexible height for long text at two hundred percent font`() = runAndroidComposeUiTest {
        val input = graphFixtures("knowledge-map.en").first()
        val label = "A long node label with many words which must wrap in a narrow list ".repeat(3)
        val layout = layoutLayeredGraph(input.copy(nodes = input.nodes.mapIndexed { index, node ->
            if (index == 0) node.copy(label = label) else node
        }))
        setSafeContent {
            ElementPreview {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        InteractiveUiGraphView(layout, null, true, true, {}, {}, Modifier.width(240.dp))
                    }
                }
            }
        }
        val bounds = onNodeWithContentDescription("$label. ").getUnclippedBoundsInRoot()
        assertThat((bounds.bottom - bounds.top).value).isGreaterThan(112f)
        assertThat((bounds.right - bounds.left).value).isAtMost(240f)
        val results = mutableListOf<TextLayoutResult>()
        onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertThat(results.single().hasVisualOverflow).isFalse()
    }

    @Test
    fun `nodes are buttons in layer reading order and select clear shows details`() = runAndroidComposeUiTest {
        val layout = layoutLayeredGraph(graphFixtures("knowledge-map.en").first())
        val selected = mutableStateOf<String?>(null)
        setSafeContent { ElementPreview { InteractiveUiGraphView(layout, selected.value, false, true, { selected.value = it }, {}) } }
        val buttons = onAllNodes(hasClickAction()).fetchSemanticsNodes()
        val labels = buttons.mapNotNull { it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.firstOrNull() }
        assertThat(labels).containsExactly("Sugar. ", "Maillard reaction. Heat creates flavour", "Flavour. ").inOrder()
        onNodeWithContentDescription("Maillard reaction. Heat creates flavour").performScrollTo().performClick()
        onNodeWithText("Heat creates flavour").assertExists()
        onNodeWithContentDescription("Maillard reaction. Heat creates flavour")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        onNodeWithContentDescription("Maillard reaction. Heat creates flavour").performClick()
        onNodeWithContentDescription("Maillard reaction. Heat creates flavour")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        runOnIdle { assertThat(selected.value).isNull() }
    }

    @Test
    fun `list alternative includes every edge including dashed and labelled relationships`() = runAndroidComposeUiTest {
        val base = graphFixtures("knowledge-map.en").first()
        val layout = layoutLayeredGraph(base.copy(edges = base.edges + InteractiveUiGraphInput.Edge("flavour", "sugar", "Feedback", true)))
        val list = mutableStateOf(false)
        setSafeContent { ElementPreview { InteractiveUiGraphView(layout, null, list.value, true, {}, { list.value = it }) } }
        onNodeWithText("Relationships as list").performClick()
        onNodeWithText("Sugar → Maillard reaction: ").assertExists()
        onNodeWithText("Maillard reaction → Flavour: ").assertExists()
        onNodeWithText("Flavour → Sugar: Feedback").assertExists()
        onNodeWithText("Heat creates flavour").assertExists()
    }

    @Test
    fun `wide graph scrolls internally at 360dp and two hundred percent text`() = runAndroidComposeUiTest {
        val layout = layoutLayeredGraph(graphFixtures("six-steps").single())
        setSafeContent {
            ElementPreview {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                InteractiveUiGraphView(layout, null, false, true, {}, {}, Modifier.width(360.dp))
            }
        }
        }
        val scroll = onNode(hasScrollAction())
        val before = scroll.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
        scroll.performTouchInput { swipeLeft() }
        waitForIdle()
        val after = scroll.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
        assertThat(after).isGreaterThan(before)
    }

    @Test
    fun `inactive graph blocks selection and mode changes`() = runAndroidComposeUiTest {
        val layout = layoutLayeredGraph(graphFixtures("knowledge-map.en").first())
        setSafeContent {
            ElementPreview {
                InteractiveUiGraphView(
                    layout,
                    null,
                    false,
                    false,
            { error("Disabled selection invoked") },
                    { error("Disabled mode invoked") }
                )
            }
        }
        onNodeWithText("Relationships as list").assertIsNotEnabled()
        onNodeWithContentDescription("Sugar. ").assertIsNotEnabled()
        onNodeWithContentDescription("Sugar. ").performClick()
        onAllNodesWithText("Heat creates flavour").assertCountEquals(0)
    }
}
