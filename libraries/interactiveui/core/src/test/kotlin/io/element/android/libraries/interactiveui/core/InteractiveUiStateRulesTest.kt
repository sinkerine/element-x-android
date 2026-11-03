/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.core

import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiAction
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiCondition
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiNode
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiParseResult
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiStatus
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiValue
import io.element.android.libraries.interactiveui.core.protocol.toInteractiveUiValueOrNull
import io.element.android.libraries.interactiveui.core.state.InteractiveUiStateKind
import io.element.android.libraries.interactiveui.core.state.InteractiveUiStateRules.acceptsStateValue
import io.element.android.libraries.interactiveui.core.state.InteractiveUiStateRules.evaluateCondition
import io.element.android.libraries.interactiveui.core.state.InteractiveUiStateRules.initialState
import io.element.android.libraries.interactiveui.core.state.InteractiveUiStateRules.mergeSavedState
import io.element.android.libraries.interactiveui.core.state.InteractiveUiStateRules.stateKind
import io.element.android.libraries.interactiveui.core.state.InteractiveUiStateRules.validateSubmission
import io.element.android.libraries.interactiveui.core.state.InteractiveUiSubmission
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from element-web apps/web/src/interactive-ui/state.test.ts at bcb08017 (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial). */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], manifest = org.robolectric.annotation.Config.NONE)
class InteractiveUiStateRulesTest {
    @Test fun `copies arrays and retains only compatible saved values`() {
        val payload = parsed("controls")
        val state = initialState(payload)
        (state.getValue("features") as InteractiveUiValue.TextList).values.let { (it as MutableList).add("a") }
        assertEquals(InteractiveUiValue.TextList(emptyList()), payload.state["features"])
        val saved = json("""{"plan":"removed","quantity":11,"name":"Alice","features":["a"],"enabled":1,"extra":true}""")
        assertEquals(payload.state + mapOf("name" to text("Alice"), "features" to InteractiveUiValue.TextList(listOf("a"))), mergeSavedState(payload, saved))
        assertEquals(payload.state, mergeSavedState(payload, json("""{"ui_id":"other","revision":0,"state":{"name":"Alice"}}""")))
        assertEquals(text("Alice"), mergeSavedState(payload.copy(revision = 1), json("""{"ui_id":"example","revision":0,"state":{"name":"Alice"}}"""))["name"])
        assertEquals(payload.state, mergeSavedState(payload, json("""{"ui_id":"example","revision":2,"state":{"name":"Alice"}}""")))
        assertEquals(payload.state, mergeSavedState(payload, JsonNull))
        assertEquals(payload.state, mergeSavedState(payload, JsonArray(emptyList())))
    }
    @Test fun `distinguishes legal raw keys from complete persistence envelopes`() {
        val payload = parsed("controls").copy(revision = 2, state =
            parsed("controls").state + mapOf("ui_id" to text("initial"), "revision" to number(0), "state" to text("initial")))
        for (saved in listOf(
                """{"ui_id":"saved","name":"Alice"}""",
                """{"revision":1,"name":"Alice"}""",
            """{"ui_id":"saved","revision":1,"name":"Alice"}""",
                """{"ui_id":"saved","revision":1,"state":"saved","name":"Alice"}""",
            """{"state":"saved","name":"Alice"}"""
            )) {
            val values = json(saved).jsonObject.mapValues { it.value.toInteractiveUiValueOrNull()!! }
            assertEquals(payload.state + values, mergeSavedState(payload, json(saved)))
        }
        assertEquals(
            payload.state + mapOf("name" to text("Alice"), "ui_id" to text("saved"), "revision" to number(1), "state" to text("saved")),
            mergeSavedState(payload, json("""{"ui_id":"example","revision":1,"state":{"name":"Alice","ui_id":"saved","revision":1,"state":"saved"}}"""))
        )
        for (metadata in listOf(
                """"ui_id":"foreign","revision":1""",
                """"ui_id":"example","revision":3""",
            """"ui_id":"example","revision":-1""",
                """"ui_id":"example","revision":"bad"""",
                """"ui_id":1,"revision":1"""
            )) {
            assertEquals(payload.state, mergeSavedState(payload, json("""{$metadata,"state":{"name":"Alice"}}""")))
        }
        assertEquals(payload.state, mergeSavedState(payload, json("""{"state":{"name":"Alice"}}""")))
        assertEquals(
            payload.state + mapOf("ui_id" to text("saved"), "revision" to number(1), "state" to InteractiveUiValue.Null),
            mergeSavedState(payload, json("""{"ui_id":"saved","revision":1,"state":null}"""))
        )
    }
    @Test fun `infers nullable string and number bindings and validates required fields`() {
        val raw = fixture("form").with("state", json("""{"name":null,"quantity":null,"plan":null}"""))
        val payload = (parse(raw) as InteractiveUiParseResult.Valid).payload
        assertEquals(InteractiveUiStateKind.Number, stateKind(payload, "quantity"))
        assertEquals(InteractiveUiStateKind.Text, stateKind(payload, "name"))
        assertEquals(
            mapOf("name" to text("Alice"), "quantity" to number(2), "plan" to text("plus")),
            mergeSavedState(payload, json("""{"name":"Alice","quantity":2,"plan":"plus"}"""))
        )
        assertEquals(InteractiveUiSubmission.Invalid(setOf("name")), validateSubmission(payload, "form", initialState(payload)))
        assertEquals(payload.state, mergeSavedState(payload, json("""{"quantity":"2","plan":"removed"}""")))
    }
    @Test fun `graph bindings accept union and revisions reset removed options`() {
        val original = parsed("knowledge-map.en")
        val graphs = original.nodes.filterIsInstance<InteractiveUiNode.Graph>()
        val second = graphs[1].copy(nodes = graphs[1].nodes.map { if (it.id == "maillard") it.copy(id = "nutrition_mechanism") else it })
        val payload = original.copy(nodes = original.nodes.map { if (it.id == second.id) second else it })
        assertEquals(text("nutrition_mechanism"), mergeSavedState(payload, json("""{"focus":"nutrition_mechanism"}"""))["focus"])
        assertEquals(text("maillard"), mergeSavedState(payload, json("""{"focus":"maillard"}"""))["focus"])
        assertEquals(InteractiveUiValue.Null, mergeSavedState(payload, json("""{"focus":"removed"}"""))["focus"])
        assertEquals(
            InteractiveUiValue.Null,
            mergeSavedState(original.copy(revision = 1), json("""{"ui_id":"knowledge-map","revision":0,"state":{"focus":"nutrition_mechanism"}}"""))["focus"]
        )
        assertEquals(
            mapOf("plan" to text("plus"), "enabled" to InteractiveUiValue.Bool(true)),
            mergeSavedState(parsed("revision-1"), json("""{"ui_id":"revision-example","revision":0,"state":{"plan":"basic","enabled":true}}"""))
        )
    }
    @Test fun `conditions have strict equality membership and negation`() {
        fun equal(value: InteractiveUiValue, negate: Boolean = false) = InteractiveUiCondition("x", InteractiveUiCondition.Comparison.EqualTo(value), negate)
        assertFalse(evaluateCondition(equal(number(1)), mapOf("x" to text("1"))))
        assertTrue(
            evaluateCondition(
                InteractiveUiCondition("x", InteractiveUiCondition.Comparison.OneOf(listOf(InteractiveUiValue.Null, text("yes"))), false),
                mapOf("x" to InteractiveUiValue.Null)
            )
        )
        assertTrue(evaluateCondition(equal(InteractiveUiValue.Bool(true), true), mapOf("x" to InteractiveUiValue.Bool(false))))
        assertFalse(evaluateCondition(equal(InteractiveUiValue.Null), emptyMap()))
        assertTrue(evaluateCondition(equal(number(0)), mapOf("x" to InteractiveUiValue.Number(-0.0))))
    }
    @Test fun `forms validate bounds required fields and all descendants in JS traversal order`() {
        val payload = parsed("form")
        assertEquals(InteractiveUiSubmission.Invalid(setOf("name")), validateSubmission(payload, "form", payload.state))
        val invalid = validateSubmission(
            payload,
            "form",
            mapOf("name" to text(" "), "quantity" to number(100), "plan" to text("removed"))
        ) as InteractiveUiSubmission.Invalid
        assertEquals(listOf("plan", "quantity", "name"), invalid.invalidKeys.toList())
        assertTrue(validateSubmission(payload, "form", mapOf(
            "name" to text("A".repeat(101)),
            "quantity" to InteractiveUiValue.Number(Double.POSITIVE_INFINITY),
            "plan" to text("basic")
        )) is InteractiveUiSubmission.Invalid)
        val good = mapOf("name" to text("Alice"), "quantity" to number(2), "plan" to text("plus"))
        assertEquals(InteractiveUiSubmission.Valid("answer", good), validateSubmission(payload, "form", good + ("extra" to text("private"))))
        assertTrue(validateSubmission(payload.copy(status = InteractiveUiStatus.Submitted), "form", good) is InteractiveUiSubmission.Invalid)
    }
    @Test fun `initial saved action and submission strings share Unicode bounds`() {
        val base = parsed("controls")
        val payload = base.copy(state = base.state + ("name" to text("😀".repeat(100))))
        assertTrue(acceptsStateValue(payload, "name", text("😀".repeat(100))))
        assertFalse(acceptsStateValue(payload, "name", text("😀".repeat(101))))
        assertFalse(acceptsStateValue(payload, "name", text("bad\u0000")))
        assertFalse(acceptsStateValue(payload, "features", InteractiveUiValue.TextList(listOf("a", "a"))))
        assertFalse(acceptsStateValue(payload, "features", InteractiveUiValue.TextList(List(51) { "a$it" })))
        assertFalse(acceptsStateValue(payload, "missing", text("x")))
        val long = payload.copy(nodes = payload.nodes.map { if (it is InteractiveUiNode.TextInput) it.copy(maxLength = 2000) else it })
        assertEquals(text("y".repeat(2000)), mergeSavedState(long, json("""{"name":"${"y".repeat(2000)}"}"""))["name"])
        assertTrue(validateSubmission(long, "submit", long.state + ("name" to text("x".repeat(2000)))) is InteractiveUiSubmission.Valid)
    }
    @Test fun `carousel requires explicit submit target and applies include filtering`() {
        val base = parsed("product-carousel")
        val carousel = base.nodes.filterIsInstance<InteractiveUiNode.Carousel>().single()
        val updated = carousel.copy(items = carousel.items.mapIndexed { i, item ->
            when (i) {
            0 -> item.copy(action = InteractiveUiAction.Submit("choose", listOf("selected"), false))
            1 -> item.copy(action = InteractiveUiAction.Submit("skip", emptyList(), false))
            else -> item
        }
        })
        val payload = base.copy(state = mapOf("selected" to text("item1"), "private" to text("local")), nodes = base.nodes.map {
            if (it.id ==
            updated.id) {
                updated
            } else {
                it
            }
        })
        assertEquals(
            InteractiveUiSubmission.Valid("choose", mapOf("selected" to text("item1"))),
            validateSubmission(payload, "carousel", payload.state, "item1")
        )
        assertEquals(InteractiveUiSubmission.Valid("skip", emptyMap()), validateSubmission(payload, "carousel", payload.state, "item2"))
        assertEquals(
            InteractiveUiSubmission.Invalid(setOf("selected")),
            validateSubmission(payload, "carousel", payload.state + ("selected" to number(42)), "item1")
        )
        for ((node, item) in listOf(
                "carousel" to null,
                "carousel" to "missing",
                "carousel" to "item3",
                "missing" to "item1",
                "root" to "item1",
                "item1" to null
            )) {
            assertEquals(InteractiveUiSubmission.Invalid(emptySet()), validateSubmission(payload, node, payload.state, item))
        }
        assertTrue(
            validateSubmission(payload.copy(status = InteractiveUiStatus.Expired), "carousel", payload.state, "item1") is InteractiveUiSubmission.Invalid
        )
        val buttons = parsed("controls")
        assertEquals(InteractiveUiSubmission.Invalid(emptySet()), validateSubmission(buttons, "submit", buttons.state, "item1"))
    }
    @Test fun `buttons restrict values to include and reject local action targets`() {
        val payload = parsed("controls")
        val state = payload.state + mapOf("name" to text("Alice"), "enabled" to InteractiveUiValue.Bool(true))
        val expected = mapOf("plan" to text("basic"), "features" to InteractiveUiValue.TextList(emptyList()), "name" to text("Alice"), "quantity" to number(1))
        assertEquals(InteractiveUiSubmission.Valid("send", expected), validateSubmission(payload, "submit", state))
        assertTrue(validateSubmission(payload, "set", state) is InteractiveUiSubmission.Invalid)
        assertTrue(validateSubmission(payload, "missing", state) is InteractiveUiSubmission.Invalid)
        assertTrue(
            validateSubmission(payload, "submit", state + ("features" to InteractiveUiValue.TextList(listOf("unknown")))) is InteractiveUiSubmission.Invalid
        )
        assertFalse((payload.nodesById["submit"] as InteractiveUiNode.Button).action.let { (it as InteractiveUiAction.Submit).singleUse })
        assertTrue((parsed("form").nodesById["form"] as InteractiveUiNode.Form).singleUse)
    }
    private fun json(value: String): JsonElement = Json.parseToJsonElement(value)
    private fun text(value: String) = InteractiveUiValue.Text(value)
    private fun number(value: Int) = InteractiveUiValue.Number(value.toDouble())
}
