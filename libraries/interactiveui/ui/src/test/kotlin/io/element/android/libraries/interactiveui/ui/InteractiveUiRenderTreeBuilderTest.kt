/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.ui

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.interactiveui.core.action.InteractiveUiEventContext
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiConstants
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiParseResult
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiParser
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiValue
import io.element.android.libraries.interactiveui.core.session.InteractiveUiPhase
import io.element.android.libraries.interactiveui.core.session.InteractiveUiSession
import io.element.android.libraries.interactiveui.core.session.InteractiveUiTableSort
import io.element.android.libraries.interactiveui.ui.chart.InteractiveUiChartModel
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiViewEvent
import io.element.android.libraries.matrix.api.media.MediaSource
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import java.io.File
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiRenderNode as Render

class InteractiveUiRenderTreeBuilderTest {
    private val fixtures = File("../core/src/test/resources/interactive-ui/fixtures/valid")
    private fun payload(name: String) = (InteractiveUiParser.parse(buildJsonObject {
        put(InteractiveUiConstants.PAYLOAD_KEY, Json.parseToJsonElement(File(fixtures, "$name.json").readText()))
    }) as InteractiveUiParseResult.Valid).payload

    @Test
    fun `every vendored catalog fixture builds a visible render tree`() = runTest {
        val kinds = mutableSetOf<String>()
        fun visit(node: Render) {
            kinds += node.javaClass.simpleName
            when (node) {
                is Render.Stack -> node.children.forEach(::visit)
                is Render.Card -> node.children.forEach(::visit)
                is Render.Tabs -> node.tabs.forEach { visit(it.content) }
                is Render.Collapsible -> node.children.forEach(::visit)
                is Render.Form -> node.children.forEach(::visit)
                else -> Unit
            }
        }
        fixtures.listFiles()!!.filter { it.extension == "json" }.forEach { file ->
            val payload = payload(file.nameWithoutExtension)
            val session = InteractiveUiSession(
                InteractiveUiEventContext("$" + "ui", "@sender:test", null),
                payload,
                null,
                backgroundScope,
                { Result.success(Unit) },
                {}
            )
            val state = InteractiveUiRenderTreeBuilder.build(session.state.value, persistentMapOf(), persistentMapOf(), false, "Sender", {})
            assertThat(state.root.id).isEqualTo(payload.root)
            visit(state.root)
        }
        assertThat(kinds).containsAtLeast(
            "Stack",
            "Card",
            "Tabs",
            "Collapsible",
            "Divider",
            "Heading",
            "Text",
            "RichText",
            "Badge",
            "KeyValue",
            "ItemList",
            "Image",
            "Table",
            "Chart",
            "Graph",
            "Button",
            "Select",
            "Choice",
            "Toggle",
            "TextInput",
            "NumberInput",
            "Form",
            "Carousel",
            "Link",
            "Unknown"
        )
    }

    @Test
    fun `draft invalid pending inactive and local modes flow into render models`() = runTest {
        val payload = payload("controls")
        val session = InteractiveUiSession(
            InteractiveUiEventContext("$" + "ui", "@sender:test", null),
            payload,
            null,
            backgroundScope,
            { Result.success(Unit) },
            {}
        )
        val state = session.state.value.copy(
            numberDrafts = mapOf("quantity" to null),
            invalidKeys = setOf("name"),
            phase = InteractiveUiPhase.Sending,
            pendingNodeId = "submit"
        )
        val rendered = InteractiveUiRenderTreeBuilder.build(state, persistentMapOf(), persistentMapOf(), false, "Sender", {})
        val nodes = (rendered.root as Render.Stack).children.associateBy { it.id }
        assertThat((nodes.getValue("number_input") as Render.NumberInput).invalid).isTrue()
        assertThat((nodes.getValue("number_input") as Render.NumberInput).value).isNull()
        assertThat((nodes.getValue("text_input") as Render.TextInput).invalid).isTrue()
        assertThat((nodes.getValue("submit") as Render.Button).pending).isTrue()
        assertThat((nodes.getValue("select") as Render.Select).enabled).isFalse()
        assertThat((nodes.getValue("linkButton") as Render.Button).external).isTrue()
    }

    @Test
    fun `visibility resolves immediately and event sink is forwarded`() = runTest {
        val json = """{"version":1,"ui_id":"visible","revision":0,"status":"active","state":{"on":false},"root":"root","nodes":[
            {"id":"root","type":"stack","direction":"vertical","children":["toggle","text"]},{"id":"toggle","type":"toggle","label":"On","bind":"on"},

            {"id":"text","type":"text","text":"Visible","visible_when":{"state":"on","equals":true}}]}""".trimIndent()
        val result = InteractiveUiParser.parse(buildJsonObject {
            put(InteractiveUiConstants.PAYLOAD_KEY, Json.parseToJsonElement(json))
        })
        assertThat(result).isInstanceOf(InteractiveUiParseResult.Valid::class.java)
        val parsed = (result as InteractiveUiParseResult.Valid).payload
        val session = InteractiveUiSession(
            InteractiveUiEventContext("$" + "ui", "@sender:test", null),
            parsed,
            null,
            backgroundScope,
            { Result.success(Unit) },
            {}
        )
        val events = mutableListOf<InteractiveUiViewEvent>()
        fun build() = InteractiveUiRenderTreeBuilder.build(session.state.value, persistentMapOf(), persistentMapOf(), false, "Sender", events::add)
        assertThat((build().root as Render.Stack).children.map { it.id }).containsExactly("toggle")
        session.setValue("toggle", InteractiveUiValue.Bool(true))
        val visible = build()
        assertThat((visible.root as Render.Stack).children.map { it.id }).containsExactly("toggle", "text").inOrder()
        visible.eventSink(InteractiveUiViewEvent.SetShowAsText(true))
        assertThat(events).containsExactly(InteractiveUiViewEvent.SetShowAsText(true))
    }

    @Test
    fun `chart graph table and carousel resolve session options and T3 sort pair boundary`() = runTest {
        for (name in listOf("chart-bar", "chart-line", "chart-pie", "chart-scatter", "six-steps", "table", "product-carousel")) {
            val payload = payload(name)
            val session = InteractiveUiSession(
                InteractiveUiEventContext("$" + "ui", "@sender:test", null),
                payload,
                null,
                backgroundScope,
                { Result.success(Unit) },
                {}
            )
            val state = session.state.value.copy(
                graphListMode = payload.nodes.map { it.id }.toSet(),
                chartDataMode = payload.nodes.map { it.id }.toSet(),
                tableSort = payload.nodes.associate { it.id to InteractiveUiTableSort("price", true) },
                carouselIndex = payload.nodes.associate { it.id to 999 }
            )
            val root = InteractiveUiRenderTreeBuilder.build(state, persistentMapOf(), persistentMapOf(), false, "Sender", {}).root
            fun leaves(node: Render): List<Render> = when (node) {
                is Render.Stack -> node.children.flatMap(::leaves)
                is Render.Card -> node.children.flatMap(::leaves)
                else -> listOf(node)
            }
            leaves(root).forEach { node ->
                when (node) {
                is Render.Chart -> {
                    assertThat(node.showData).isTrue()
                    if (name == "chart-pie") assertThat(node.chart).isInstanceOf(InteractiveUiChartModel.Pie::class.java)
                    if (name == "chart-scatter") assertThat(node.chart).isInstanceOf(InteractiveUiChartModel.Scatter::class.java)
                }
                is Render.Graph -> {
                    assertThat(node.showAsList).isTrue()
                    assertThat(node.layout.nodes).isNotEmpty()
                }
                is Render.Table -> {
                    assertThat(node.sort).isEqualTo(InteractiveUiTableSort("price", true))
                    assertThat(node.rows).isNotEmpty()
                }
                is Render.Carousel -> assertThat(node.index).isEqualTo(node.items.lastIndex)
                else -> Unit
            }
            }
        }
    }

    @Test
    fun `media protection traverses all containers and reveals every nested media source`() {
        val image = Render.Image("image", "Alt", MediaSource("mxc://example.org/image"), "image/png", 1f, false)
        val carousel = Render.Carousel("carousel", "Items", 0, persistentListOf(Render.Carousel.Item(
            "item",
            image.source,
            "Alt",
            "Title",
            null,
            persistentListOf(),
            null,
            null,
            false,
            false
        )), true)
        val stack = Render.Stack("stack", false, androidx.compose.ui.unit.Dp(0f), persistentListOf(image, carousel))
        val card = Render.Card("card", null, persistentListOf(stack))
        val form = Render.Form("form", "Submit", persistentListOf(card), true, false)
        val collapse = Render.Collapsible("collapse", "Collapse", false, true, persistentListOf(form))
        val root = Render.Tabs("tabs", "one", persistentListOf(Render.Tabs.Tab("one", "One", collapse)), true)
        val state = viewState(root)
        val protected = state.withHiddenMedia(true)
        fun images(node: Render): List<Boolean> = when (node) {
            is Render.Image -> listOf(node.hidden)
            is Render.Carousel -> node.items.map { it.hidden }
            is Render.Stack -> node.children.flatMap(::images)
            is Render.Card -> node.children.flatMap(::images)
            is Render.Tabs -> node.tabs.flatMap { images(it.content) }
            is Render.Collapsible -> node.children.flatMap(::images)
            is Render.Form -> node.children.flatMap(::images)
            else -> emptyList()
        }
        assertThat(images(protected.root)).containsExactly(true, true)
        assertThat(images(protected.withHiddenMedia(false).root)).containsExactly(false, false)
        assertThat(images(state.root)).containsExactly(false, false)
    }
}
