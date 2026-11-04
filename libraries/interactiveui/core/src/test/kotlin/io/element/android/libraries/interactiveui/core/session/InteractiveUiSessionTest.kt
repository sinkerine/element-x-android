/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.core.session

import io.element.android.libraries.interactiveui.core.action.InteractiveUiActionContentBuilder
import io.element.android.libraries.interactiveui.core.action.InteractiveUiEventContext
import io.element.android.libraries.interactiveui.core.parsed
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiAction
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiCondition
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiConstants
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiNode
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiPayload
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiStatus
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiValue
import io.element.android.libraries.interactiveui.core.state.InteractiveUiStateRules
import io.element.android.libraries.interactiveui.core.state.InteractiveUiSubmission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ported from element-web apps/web/src/viewmodels/message-body/InteractiveUiBodyViewModel.test.ts at bcb08017
 * (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class InteractiveUiSessionTest {
    private val context = InteractiveUiEventContext("\$original", "@sender:example.org", "\$thread")
    private val sent = mutableListOf<JsonObject>()
    private val saved = mutableListOf<JsonObject>()

    @Test
    fun `bound controls and declarative actions update locally without sending`() = runTest {
        val session = session()
        session.setValue("select", text("plus"))
        session.setValue("choice", InteractiveUiValue.TextList(listOf("a", "b")))
        session.setValue("toggle", InteractiveUiValue.Bool(true))
        session.setValue("text_input", text("Alice"))
        session.setValue("number_input", number(3))
        assertEquals(mapOf(
            "plan" to text("plus"),
            "features" to InteractiveUiValue.TextList(listOf("a", "b")),
            "enabled" to InteractiveUiValue.Bool(true),
            "name" to text("Alice"),
            "quantity" to number(3)
        ), session.state.value.values)
        session.activate("toggleButton")
        assertEquals(InteractiveUiValue.Bool(false), session.state.value.values["enabled"])
        session.setValue("select", text("basic"))
        session.activate("set")
        assertEquals(text("plus"), session.state.value.values["plan"])
        advanceUntilIdle()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `invalid targets types and options do not change state`() = runTest {
        val session = session()
        val before = session.state.value
        session.setValue("missing", text("x"))
        session.setValue("select", text("missing"))
        session.setValue("toggle", text("true"))
        session.setValue("choice", InteractiveUiValue.TextList(listOf("a", "a")))
        session.setValue("number_input", number(11))
        session.setValue("text_input", text("x".repeat(101)))
        session.activate("select")
        session.activateCarouselItem("select", "item1")
        session.submitForm("submit")
        session.openLink("submit")
        session.selectGraphNode("select", null)
        session.setGraphListMode("select", true)
        session.setChartDataMode("select", true)
        session.sortTable("select", "x")
        session.setCarouselIndex("select", 0)
        session.confirmOpenUrl()
        session.retryAfterOverdue()
        assertEquals(before, session.state.value)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `tabs and bound and unbound collapsibles use the appropriate state`() = runTest {
        val payload = parsed("layout")
        val bound = payload.nodes.filterIsInstance<InteractiveUiNode.Collapsible>().single()
        val local = bound.copy(id = "local", bind = null, children = emptyList())
        val session = session(augment(payload, listOf(local)))
        session.setValue("tabs", text("one"))
        session.setValue(bound.id, InteractiveUiValue.Bool(false))
        session.setValue("local", InteractiveUiValue.Bool(true))
        assertEquals(text("one"), session.state.value.values["tab"])
        assertEquals(InteractiveUiValue.Bool(false), session.state.value.values["open"])
        assertEquals(mapOf("local" to true), session.state.value.collapsibleOpen)
        session.setValue("local", text("true"))
        assertEquals(mapOf("local" to true), session.state.value.collapsibleOpen)
        advanceUntilIdle()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `hidden nodes and descendants cannot be mutated until condition changes`() = runTest {
        val payload = controlsReady()
        val gated = InteractiveUiNode.Card("gate", condition("enabled", InteractiveUiValue.Bool(true)), null, null, listOf("text_input", "set"))
        val root = (payload.nodesById.getValue("root") as InteractiveUiNode.Stack).copy(children = listOf("gate", "toggle"))
        val session = session(payload.copy(nodes = payload.nodes.map { if (it.id == "root") root else it } + gated))
        session.setValue("text_input", text("hidden"))
        session.activate("set")
        assertEquals(text("Alice"), session.state.value.values["name"])
        assertEquals(text("basic"), session.state.value.values["plan"])
        session.setValue("toggle", InteractiveUiValue.Bool(true))
        session.setValue("text_input", text("visible"))
        session.activate("set")
        assertEquals(text("visible"), session.state.value.values["name"])
        assertEquals(text("plus"), session.state.value.values["plan"])
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `shared graph selection accepts other branch ids and local graph clears to null`() = runTest {
        val payload = parsed("knowledge-map.en")
        val graph = payload.nodes.filterIsInstance<InteractiveUiNode.Graph>().first()
        val second = payload.nodes.filterIsInstance<InteractiveUiNode.Graph>()[1]
        val local = graph.copy(id = "local", bindSelected = null, visibleWhen = null)
        val session = session(augment(payload, listOf(local)))
        session.selectGraphNode(graph.id, "maillard")
        assertEquals(text("maillard"), session.state.value.values["focus"])
        session.setValue("select", text("nutrition"))
        assertEquals(text("maillard"), session.state.value.values["focus"])
        session.selectGraphNode(second.id, "missing")
        assertEquals(text("maillard"), session.state.value.values["focus"])
        session.selectGraphNode("local", "sugar")
        assertEquals("sugar", session.state.value.graphSelection["local"])
        session.selectGraphNode("local", null)
        assertTrue(session.state.value.graphSelection.containsKey("local"))
        assertNull(session.state.value.graphSelection["local"])
        session.selectGraphNode(second.id, null)
        assertEquals(InteractiveUiValue.Null, session.state.value.values["focus"])
        advanceUntilIdle()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `view modes sorting and carousel indices toggle locally and reject invalid input`() = runTest {
        val session = session(catalog())
        session.setGraphListMode("graph", true)
        session.setChartDataMode("chart", true)
        session.sortTable("table", "a")
        session.setCarouselIndex("carousel", 1)
        assertEquals(setOf("graph"), session.state.value.graphListMode)
        assertEquals(setOf("chart"), session.state.value.chartDataMode)
        assertEquals(InteractiveUiTableSort("a", false), session.state.value.tableSort["table"])
        assertEquals(1, session.state.value.carouselIndex["carousel"])
        session.sortTable("table", "a")
        assertEquals(InteractiveUiTableSort("a", true), session.state.value.tableSort["table"])
        session.sortTable("table", "b")
        assertEquals(InteractiveUiTableSort("b", false), session.state.value.tableSort["table"])
        val before = session.state.value
        session.sortTable("table", "missing")
        session.sortTable("unsortable", "a")
        session.setCarouselIndex("carousel", -1)
        session.setCarouselIndex("carousel", 999)
        session.selectGraphNode("graph", "missing")
        assertEquals(before, session.state.value)
        session.setGraphListMode("graph", false)
        session.setChartDataMode("chart", false)
        assertTrue(session.state.value.graphListMode.isEmpty())
        assertTrue(session.state.value.chartDataMode.isEmpty())
        advanceUntilIdle()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `all control mutators are noops in every inactive phase`() = runTest {
        for (phase in InteractiveUiPhase.entries.filter { it != InteractiveUiPhase.Active }) {
            val response = CompletableDeferred<Result<Unit>>()
            val base = catalog()
            val status = when (phase) {
                InteractiveUiPhase.Submitted -> InteractiveUiStatus.Submitted
                InteractiveUiPhase.Expired -> InteractiveUiStatus.Expired
                InteractiveUiPhase.Superseded -> InteractiveUiStatus.Superseded
                else -> InteractiveUiStatus.Active
            }
            val session = session(base.copy(status = status), send = { response.await() })
            if (phase == InteractiveUiPhase.Sending || phase == InteractiveUiPhase.AwaitingConfirmation) {
                session.submitForm("form")
                runCurrent()
                if (phase == InteractiveUiPhase.AwaitingConfirmation) {
                    response.complete(Result.success(Unit))
                    runCurrent()
                }
            }
            assertEquals(phase, session.state.value.phase)
            val before = session.state.value
            mutateEveryControl(session)
            assertEquals(before, session.state.value)
            session.setShowAsText(true)
            assertTrue(session.state.value.showAsText)
            session.setShowAsText(false)
            assertFalse(session.state.value.showAsText)
            session.dispose()
            response.complete(Result.success(Unit))
        }
    }

    @Test
    fun `required fields block button and form with inline invalid keys`() = runTest {
        val button = session()
        button.activate("submit")
        assertEquals(setOf("name"), button.state.value.invalidKeys)
        val form = session(parsed("form"))
        form.setValue("text_input", text(" \u00a0"))
        form.submitForm("form")
        assertEquals(setOf("name"), form.state.value.invalidKeys)
        form.setValue("text_input", text("Alice"))
        assertTrue(form.state.value.invalidKeys.isEmpty())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `out of range and partial number drafts block submission and clear when valid`() = runTest {
        for (draft in listOf(InteractiveUiValue.Null, number(0), number(11))) {
            val session = session(numberField(controlsReady()))
            session.setValue("number_input", draft)
            assertEquals(number(1), session.state.value.values["quantity"])
            assertTrue(session.state.value.numberDrafts.containsKey("quantity"))
            session.activate("submit")
            assertEquals(setOf("quantity"), session.state.value.invalidKeys)
            assertEquals(InteractiveUiPhase.Active, session.state.value.phase)
            session.setValue("number_input", number(2))
            assertTrue(session.state.value.invalidKeys.isEmpty())
            assertTrue(session.state.value.numberDrafts.isEmpty())
            session.dispose()
        }
        advanceUntilIdle()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `nonfinite and wrong type numbers are ignored while nullable drafts still block`() = runTest {
        val base = numberField(controlsReady())
        val session = session(base.copy(state = base.state + ("quantity" to InteractiveUiValue.Null)))
        val before = session.state.value
        session.setValue("number_input", InteractiveUiValue.Number(Double.NaN))
        session.setValue("number_input", InteractiveUiValue.Number(Double.POSITIVE_INFINITY))
        session.setValue("number_input", text("12,5"))
        assertEquals(before, session.state.value)
        session.setValue("number_input", InteractiveUiValue.Null)
        session.activate("submit")
        assertEquals(setOf("quantity"), session.state.value.invalidKeys)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `button include filtering excludes unrelated drafts and values`() = runTest {
        val base = numberField(controlsReady())
        val session = session(base.copy(nodes = base.nodes.map { node ->
            if (node.id == "submit") (node as InteractiveUiNode.Button).copy(action = InteractiveUiAction.Submit("send", listOf("plan"), false)) else node
        }))
        session.setValue("number_input", InteractiveUiValue.Null)
        session.activate("submit")
        advanceUntilIdle()
        val action = sent.single().getValue(InteractiveUiConstants.ACTION_KEY).jsonObject
        assertEquals(Json.parseToJsonElement("""{"plan":"basic"}"""), action["values"])
        assertFalse(action.toString().contains("Alice"))
    }

    @Test
    fun `forms validate hidden nested descendants and filter other state keys`() = runTest {
        val base = parsed("form")
        val nested = InteractiveUiNode.Card("nested", condition("plan", text("plus")), null, null, listOf("text_input", "number_input", "select"))
        val payload = base.copy(state = base.state + ("private" to text("local")), nodes = base.nodes.map {
            if (it is InteractiveUiNode.Form) it.copy(children = listOf("nested")) else it
        } + nested)
        val session = session(payload)
        session.submitForm("form")
        assertEquals(setOf("name"), session.state.value.invalidKeys)
        assertTrue(sent.isEmpty())
        session.onRevision(payload.copy(revision = 1, state = payload.state + ("name" to text("Alice"))))
        // Compatible old empty values win the revision merge, including hidden form descendants.
        session.setValue("select", text("plus"))
        assertEquals(text("basic"), session.state.value.values["plan"])
        val ready = session(payload.copy(state = payload.state + ("name" to text("Alice"))))
        ready.submitForm("form")
        runCurrent()
        val values = sent.single().getValue(InteractiveUiConstants.ACTION_KEY).jsonObject.getValue("values").jsonObject
        assertEquals(setOf("name", "quantity", "plan"), values.keys)
    }

    @Test
    fun `repeatable send stays sending until server result then reenables with fresh nonce and exact wire content`() = runTest {
        val response = CompletableDeferred<Result<Unit>>()
        val payload = controlsReady()
        var nonceIndex = 0
        val session = session(payload, send = {
            sent.add(it)
            response.await()
        }, nonce = { "nonce${++nonceIndex}" })
        session.activate("submit")
        assertEquals(InteractiveUiPhase.Sending, session.state.value.phase)
        assertEquals("submit", session.state.value.pendingNodeId)
        session.activate("submit")
        runCurrent()
        assertEquals(1, sent.size)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(InteractiveUiPhase.Sending, session.state.value.phase)
        assertFalse(session.state.value.confirmationOverdue)
        val submission = InteractiveUiStateRules.validateSubmission(payload, "submit", payload.state) as InteractiveUiSubmission.Valid
        assertEquals(InteractiveUiActionContentBuilder.build(context, payload, submission.actionId, submission.values, "Send", "nonce1"), sent.single())
        response.complete(Result.success(Unit))
        runCurrent()
        assertEquals(InteractiveUiPhase.Active, session.state.value.phase)
        assertNull(session.state.value.pendingNodeId)
        session.activate("submit")
        runCurrent()
        assertEquals(2, sent.size)
        assertNotEquals(
            sent[0].getValue(InteractiveUiConstants.ACTION_KEY).jsonObject["nonce"],
            sent[1].getValue(InteractiveUiConstants.ACTION_KEY).jsonObject["nonce"]
        )
    }

    @Test
    fun `default nonce is fresh 32 character base64url on each send`() = runTest {
        val session = InteractiveUiSession(context, controlsReady(), null, this, {
            sent.add(it)
            Result.success(Unit)
        }, { saved.add(it) })
        repeat(2) {
            session.activate("submit")
            runCurrent()
        }
        val nonces = sent.map { it.getValue(InteractiveUiConstants.ACTION_KEY).jsonObject.getValue("nonce").jsonPrimitive.content }
        assertEquals(2, nonces.toSet().size)
        assertTrue(nonces.all { Regex("[A-Za-z0-9_-]{32}").matches(it) })
    }

    @Test
    fun `single use waits exactly eight seconds and retry sends no event`() = runTest {
        val session = session(formReady())
        session.submitForm("form")
        assertEquals(InteractiveUiPhase.Sending, session.state.value.phase)
        runCurrent()
        assertEquals(InteractiveUiPhase.AwaitingConfirmation, session.state.value.phase)
        assertNull(session.state.value.pendingNodeId)
        session.retryAfterOverdue()
        assertEquals(InteractiveUiPhase.AwaitingConfirmation, session.state.value.phase)
        advanceTimeBy(7_999)
        runCurrent()
        assertFalse(session.state.value.confirmationOverdue)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(session.state.value.confirmationOverdue)
        assertEquals(InteractiveUiNotice.WaitingForSender, session.state.value.notice)
        assertEquals(1, sent.size)
        session.retryAfterOverdue()
        assertEquals(InteractiveUiPhase.Active, session.state.value.phase)
        assertFalse(session.state.value.confirmationOverdue)
        assertNull(session.state.value.notice)
        runCurrent()
        assertEquals(1, sent.size)
    }

    @Test
    fun `explicit single use button and repeatable form override defaults`() = runTest {
        val base = controlsReady()
        val single = session(base.copy(nodes = base.nodes.map {
            if (it.id == "submit") (it as InteractiveUiNode.Button).copy(action = (it.action as InteractiveUiAction.Submit).copy(singleUse = true)) else it
        }))
        single.activate("submit")
        runCurrent()
        assertEquals(InteractiveUiPhase.AwaitingConfirmation, single.state.value.phase)
        val form = formReady()
        val repeatable = session(form.copy(nodes = form.nodes.map { if (it is InteractiveUiNode.Form) it.copy(singleUse = false) else it }))
        repeatable.submitForm("form")
        runCurrent()
        assertEquals(InteractiveUiPhase.Active, repeatable.state.value.phase)
    }

    @Test
    fun `carousel actions use explicit item label include and lifecycle`() = runTest {
        val base = catalog()
        val carousel = base.nodesById.getValue("carousel") as InteractiveUiNode.Carousel
        val payload = base.copy(nodes = base.nodes.map {
            if (it.id == "carousel") {
                carousel.copy(items = carousel.items.mapIndexed { index, item ->
                if (index == 0) item.copy(action = InteractiveUiAction.Submit("choose", listOf("plan"), false)) else item
            })
            } else {
                it
            }
        })
        val session = session(payload)
        session.activateCarouselItem("carousel", "missing")
        assertTrue(sent.isEmpty())
        session.activateCarouselItem("carousel", "item1")
        assertEquals("carousel", session.state.value.pendingNodeId)
        runCurrent()
        assertEquals(InteractiveUiPhase.Active, session.state.value.phase)
        assertEquals(InteractiveUiActionContentBuilder.build(
            context,
            payload,
            "choose",
            mapOf("plan" to text("basic")),
            carousel.items[0].title,
            "fixed"
        ), sent.single())
        session.activateCarouselItem("carousel", "item2")
        assertTrue(session.state.value.urlRequest is InteractiveUiUrlRequest.Confirming)
        assertEquals(1, sent.size)
    }

    @Test
    fun `failed result and thrown exceptions restore controls without exception content`() = runTest {
        for (throws in listOf(false, true)) {
            val session = session(controlsReady(), send = {
                if (throws) error("private content") else Result.failure(IllegalStateException("private content"))
            })
            session.activate("submit")
            runCurrent()
            assertEquals(InteractiveUiPhase.Active, session.state.value.phase)
            assertEquals(InteractiveUiNotice.SendFailed, session.state.value.notice)
            assertNull(session.state.value.pendingNodeId)
        }
    }

    @Test
    fun `nonce failures restore controls before sending`() = runTest {
        val session = session(controlsReady(), nonce = { error("unavailable") })
        session.activate("submit")
        runCurrent()
        assertEquals(InteractiveUiNotice.SendFailed, session.state.value.notice)
        assertEquals(InteractiveUiPhase.Active, session.state.value.phase)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `cancellation propagates and clears sending without failure notice`() = runTest {
        val job = Job(coroutineContext[Job])
        var cancelled = false
        val session = session(controlsReady(), scope = CoroutineScope(coroutineContext + job), send = {
            try {
                CompletableDeferred<Unit>().await()
                Result.success(Unit)
            } finally {
                cancelled = true
            }
        })
        session.activate("submit")
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(cancelled)
        assertEquals(InteractiveUiPhase.Active, session.state.value.phase)
        assertNull(session.state.value.notice)
        assertNull(session.state.value.pendingNodeId)
    }

    @Test
    fun `cancellation before dispatch cannot leave session sending or invoke transport`() = runTest {
        val job = Job(coroutineContext[Job])
        val session = session(controlsReady(), scope = CoroutineScope(coroutineContext + job))
        session.activate("submit")
        assertEquals(InteractiveUiPhase.Sending, session.state.value.phase)
        job.cancel()
        runCurrent()
        assertEquals(InteractiveUiPhase.Active, session.state.value.phase)
        assertNull(session.state.value.pendingNodeId)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `terminal revision followed by active revision still blocks controls until original send returns`() = runTest {
        val response = CompletableDeferred<Result<Unit>>()
        val payload = controlsReady()
        val session = session(payload, send = {
            sent.add(it)
            response.await()
        })
        session.activate("submit")
        runCurrent()
        session.onRevision(payload.copy(revision = 1, status = InteractiveUiStatus.Expired))
        session.onRevision(payload.copy(revision = 2))
        assertEquals(InteractiveUiPhase.Sending, session.state.value.phase)
        session.activate("submit")
        assertEquals(1, sent.size)
        response.complete(Result.success(Unit))
        runCurrent()
        assertEquals(InteractiveUiPhase.Active, session.state.value.phase)
        assertNull(session.state.value.pendingNodeId)
    }

    @Test
    fun `returned cancellation is propagated and not converted to send failure`() = runTest {
        val session = session(controlsReady(), send = { Result.failure(CancellationException()) })
        session.activate("submit")
        runCurrent()
        assertEquals(InteractiveUiPhase.Active, session.state.value.phase)
        assertNull(session.state.value.notice)
    }

    @Test
    fun `new revisions own both successful and failed inflight results`() = runTest {
        for (status in InteractiveUiStatus.entries) {
            for (success in listOf(true, false)) {
                val response = CompletableDeferred<Result<Unit>>()
                val payload = formReady()
                val session = session(payload, send = { response.await() })
                session.submitForm("form")
                runCurrent()
                val revision = payload.copy(revision = 1, status = status, statusText = "new notice")
                session.onRevision(revision)
                val expected = if (status == InteractiveUiStatus.Active) InteractiveUiPhase.Sending else InteractiveUiPhase.valueOf(status.name)
                assertEquals(expected, session.state.value.phase)
                assertEquals("form", session.state.value.pendingNodeId)
                val before = session.state.value
                session.submitForm("form")
                assertEquals(before, session.state.value)
                response.complete(if (success) Result.success(Unit) else Result.failure(IllegalStateException()))
                runCurrent()
                assertEquals(InteractiveUiPhase.valueOf(status.name), session.state.value.phase)
                assertEquals(InteractiveUiNotice.PayloadText("new notice"), session.state.value.notice)
                assertNull(session.state.value.pendingNodeId)
                advanceTimeBy(8_000)
                runCurrent()
                assertFalse(session.state.value.confirmationOverdue)
                session.dispose()
            }
        }
    }

    @Test
    fun `awaiting revision cancels confirmation timer and adopts terminal notice`() = runTest {
        val payload = formReady()
        val session = session(payload)
        session.submitForm("form")
        runCurrent()
        advanceTimeBy(7_999)
        session.onRevision(payload.copy(revision = 1, status = InteractiveUiStatus.Submitted, statusText = "Recorded"))
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(InteractiveUiPhase.Submitted, session.state.value.phase)
        assertEquals(InteractiveUiNotice.PayloadText("Recorded"), session.state.value.notice)
        assertFalse(session.state.value.confirmationOverdue)
        session.retryAfterOverdue()
        assertEquals(InteractiveUiPhase.Submitted, session.state.value.phase)
    }

    @Test
    fun `equal and lower revisions do not clear sending or overdue state`() = runTest {
        val payload = formReady().copy(revision = 2)
        val session = session(payload)
        session.submitForm("form")
        runCurrent()
        advanceTimeBy(8_000)
        runCurrent()
        val before = session.state.value
        session.onRevision(payload.copy(status = InteractiveUiStatus.Submitted))
        session.onRevision(payload.copy(revision = 1, status = InteractiveUiStatus.Expired))
        assertEquals(before, session.state.value)
    }

    @Test
    fun `revision merges compatible values resets removed options and clears all transient state`() = runTest {
        val payload = numberField(catalog())
        val session = session(payload)
        session.setValue("select", text("plus"))
        session.setValue("text_input", text("retained"))
        session.setValue("number_input", InteractiveUiValue.Null)
        session.activate("submit")
        session.setValue("local", InteractiveUiValue.Bool(true))
        session.selectGraphNode("graph", "sugar")
        session.setGraphListMode("graph", true)
        session.setChartDataMode("chart", true)
        session.sortTable("table", "a")
        session.setCarouselIndex("carousel", 1)
        session.openLink("link")
        session.setShowAsText(true)
        val revision = payload.copy(revision = 1, state = payload.state + ("plan" to text("basic")), nodes = payload.nodes.map {
            if (it is InteractiveUiNode.Select) it.copy(options = it.options.filter { option -> option.value == "basic" }) else it
        })
        session.onRevision(revision)
        val state = session.state.value
        assertEquals(text("retained"), state.values["name"])
        assertEquals(text("basic"), state.values["plan"])
        assertEquals(number(1), state.values["quantity"])
        assertTrue(state.numberDrafts.isEmpty())
        assertTrue(state.invalidKeys.isEmpty())
        assertTrue(state.collapsibleOpen.isEmpty())
        assertTrue(state.graphSelection.isEmpty())
        assertTrue(state.graphListMode.isEmpty())
        assertTrue(state.chartDataMode.isEmpty())
        assertTrue(state.tableSort.isEmpty())
        assertTrue(state.carouselIndex.isEmpty())
        assertNull(state.urlRequest)
        assertTrue(state.showAsText)
    }

    @Test
    fun `saved state applies initially and while pristine but never overwrites user changes`() = runTest {
        val session = session(savedState = json("""{"ui_id":"example","revision":0,"state":{"name":"saved"}}"""))
        assertEquals(text("saved"), session.state.value.values["name"])
        session.applySavedState(json("""{"name":"loaded","quantity":99}"""))
        assertEquals(text("loaded"), session.state.value.values["name"])
        assertEquals(number(1), session.state.value.values["quantity"])
        session.setValue("text_input", text("typed"))
        session.applySavedState(json("""{"name":"late"}"""))
        assertEquals(text("typed"), session.state.value.values["name"])
        val invalidOnly = session()
        invalidOnly.setValue("select", text("removed"))
        invalidOnly.applySavedState(json("""{"name":"loaded"}"""))
        assertEquals(text("loaded"), invalidOnly.state.value.values["name"])
    }

    @Test
    fun `draft and local view changes make session nonpristine`() = runTest {
        val draft = session(numberField(controlsReady()))
        draft.setValue("number_input", InteractiveUiValue.Null)
        draft.applySavedState(json("""{"name":"late"}"""))
        assertEquals(text("Alice"), draft.state.value.values["name"])
        val view = session(catalog())
        view.setGraphListMode("graph", true)
        view.applySavedState(json("""{"name":"late"}"""))
        assertEquals(text("Alice"), view.state.value.values["name"])
    }

    @Test
    fun `saved state remains applicable after revision if no user changes happened`() = runTest {
        val payload = controlsReady()
        val session = session(payload)
        session.onRevision(payload.copy(revision = 1))
        session.applySavedState(json("""{"ui_id":"example","revision":0,"state":{"name":"loaded"}}"""))
        assertEquals(text("loaded"), session.state.value.values["name"])
    }

    @Test
    fun `settled carousel selection survives session recreation without persisting transient state`() = runTest {
        val payload = catalog()
        val first = session(payload)
        first.setCarouselIndex("carousel", 3)
        first.setGraphListMode("graph", true)
        first.openLink("link")
        advanceTimeBy(200)
        runCurrent()
        val restored = session(payload, savedState = saved.single())
        assertEquals(3, restored.state.value.carouselIndex["carousel"])
        assertTrue(restored.state.value.graphListMode.isEmpty())
        assertNull(restored.state.value.urlRequest)
        assertEquals(InteractiveUiPhase.Active, restored.state.value.phase)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `carousel restore validates envelope identity revision shape and item ids`() = runTest {
        val payload = catalog()
        val first = session(payload)
        first.setCarouselIndex("carousel", 3)
        advanceTimeBy(200)
        runCurrent()
        val envelope = saved.single()
        val invalid = listOf(
            JsonObject(envelope - "state"),
            JsonObject(envelope + ("ui_id" to JsonPrimitive("other"))),
            JsonObject(envelope + ("revision" to JsonPrimitive(1))),
            JsonObject(envelope + ("revision" to JsonPrimitive("0"))),
            JsonObject(envelope + ("android_view" to json("[]"))),
            JsonObject(envelope + ("android_view" to json("""{"carousel_selected_items":{"carousel":"removed","unknown":"item1"}}"""))),
            JsonObject(envelope + ("android_view" to json("""{"carousel_selected_items":{"carousel":3}}"""))),
        )
        invalid.forEach { assertTrue(session(payload, savedState = it).state.value.carouselIndex.isEmpty()) }
        assertTrue(session(payload.copy(revision = 1), savedState = envelope).state.value.carouselIndex.isEmpty())
        val legacy = JsonObject(envelope - "android_view")
        assertEquals(text("Alice"), session(payload, savedState = legacy).state.value.values["name"])
        assertTrue(session(payload, savedState = legacy).state.value.carouselIndex.isEmpty())
        val late = session(payload)
        late.applySavedState(envelope)
        assertEquals(3, late.state.value.carouselIndex["carousel"])
        val touched = session(payload)
        touched.setCarouselIndex("carousel", 1)
        touched.applySavedState(envelope)
        assertEquals(1, touched.state.value.carouselIndex["carousel"])
    }

    @Test
    fun `persistence debounces at exactly 200ms and contains only validated values`() = runTest {
        val session = session(numberField(catalog()))
        session.setValue("text_input", text("first"))
        advanceTimeBy(199)
        runCurrent()
        assertTrue(saved.isEmpty())
        session.setValue("text_input", text("last"))
        session.setValue("number_input", number(99))
        session.setGraphListMode("graph", true)
        advanceTimeBy(199)
        runCurrent()
        assertTrue(saved.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        val envelope = saved.single()
        assertEquals(setOf("ui_id", "revision", "state"), envelope.keys)
        assertEquals("example", envelope.getValue("ui_id").jsonPrimitive.content)
        assertEquals("0", envelope.getValue("revision").jsonPrimitive.content)
        val values = envelope.getValue("state").jsonObject
        assertEquals("last", values.getValue("name").jsonPrimitive.content)
        assertEquals(1.0, values.getValue("quantity").jsonPrimitive.double, 0.0)
        assertEquals(session.state.value.values.keys, values.keys)
    }

    @Test
    fun `dispose flushes once cancels timers and ignores every later callback`() = runTest {
        val session = session(catalog())
        session.setValue("text_input", text("latest"))
        session.submitForm("form")
        runCurrent()
        val before = session.state.value
        session.dispose()
        session.dispose()
        mutateEveryControl(session)
        session.setShowAsText(true)
        session.onRevision(catalog().copy(revision = 1))
        session.applySavedState(json("""{"name":"late"}"""))
        session.confirmOpenUrl()
        session.clearUrlRequest()
        session.retryAfterOverdue()
        runCurrent()
        assertEquals(1, saved.size)
        assertEquals("latest", saved.single().getValue("state").jsonObject.getValue("name").jsonPrimitive.content)
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(before, session.state.value)
        assertEquals(1, saved.size)
    }

    @Test
    fun `dispose ignores late success and failure from a suspended send`() = runTest {
        for (success in listOf(true, false)) {
            val response = CompletableDeferred<Result<Unit>>()
            val session = session(formReady(), send = { response.await() })
            session.submitForm("form")
            runCurrent()
            session.dispose()
            val before = session.state.value
            response.complete(if (success) Result.success(Unit) else Result.failure(IllegalStateException()))
            advanceUntilIdle()
            assertEquals(before, session.state.value)
        }
    }

    @Test
    fun `persistence failures are optional and dispose serializes after an ongoing write`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<JsonObject>()
        val session = session(persist = { envelope ->
            if (writes.isEmpty()) {
                withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
            }
            writes.add(envelope)
        })
        session.setValue("text_input", text("first"))
        advanceTimeBy(200)
        runCurrent()
        assertTrue(entered.isCompleted)
        session.setValue("text_input", text("last"))
        session.dispose()
        runCurrent()
        assertTrue(writes.isEmpty())
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("first", "last"), writes.map { it.getValue("state").jsonObject.getValue("name").jsonPrimitive.content })
        val failing = session(persist = { error("private data") })
        failing.setValue("text_input", text("value"))
        advanceTimeBy(200)
        runCurrent()
        failing.dispose()
        runCurrent()
        assertEquals(text("value"), failing.state.value.values["name"])
    }

    @Test
    fun `url link and button require confirmation and cancellation opens nothing`() = runTest {
        val session = session(catalog())
        session.openLink("link")
        assertEquals(InteractiveUiUrlRequest.Confirming("https://example.org/"), session.state.value.urlRequest)
        session.clearUrlRequest()
        session.confirmOpenUrl()
        assertNull(session.state.value.urlRequest)
        session.activate("linkButton")
        session.confirmOpenUrl()
        assertEquals(InteractiveUiUrlRequest.Approved("https://example.org/"), session.state.value.urlRequest)
        session.confirmOpenUrl()
        assertEquals(InteractiveUiUrlRequest.Approved("https://example.org/"), session.state.value.urlRequest)
        session.clearUrlRequest()
        assertNull(session.state.value.urlRequest)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `url requests cannot be confirmed after a revision send or disposal`() = runTest {
        val payload = catalog()
        val session = session(payload)
        session.openLink("link")
        session.onRevision(payload.copy(revision = 1))
        session.confirmOpenUrl()
        assertNull(session.state.value.urlRequest)
        session.openLink("link")
        session.activate("submit")
        session.confirmOpenUrl()
        assertNull(session.state.value.urlRequest)
        runCurrent()
        session.openLink("link")
        session.dispose()
        session.confirmOpenUrl()
        assertTrue(session.state.value.urlRequest is InteractiveUiUrlRequest.Confirming)
    }

    @Test
    fun `state flow emits immutable snapshots while local changes happen`() = runTest {
        val session = session()
        val states = mutableListOf<InteractiveUiSessionState>()
        val collector = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            session.state.collect { states.add(it) }
        }
        val list = mutableListOf("a")
        session.setValue("choice", InteractiveUiValue.TextList(list))
        list.add("b")
        session.setValue("text_input", text("Alice"))
        assertEquals(3, states.size)
        assertEquals(InteractiveUiValue.TextList(emptyList()), states[0].values["features"])
        assertEquals(InteractiveUiValue.TextList(listOf("a")), states[1].values["features"])
        collector.cancel()
    }

    private fun TestScope.session(
        payload: InteractiveUiPayload = parsed("controls"),
        savedState: JsonElement? = null,
        scope: CoroutineScope = this,
        send: suspend (JsonObject) -> Result<Unit> = {
            sent.add(it)
            Result.success(Unit)
        },
        persist: suspend (JsonObject) -> Unit = { saved.add(it) },
        nonce: () -> String = { "fixed" },
    ) = InteractiveUiSession(context, payload, savedState, scope, send, persist, nonce)

    private fun controlsReady() = parsed("controls").let { it.copy(state = it.state + ("name" to text("Alice"))) }
    private fun formReady() = parsed("form").let { it.copy(state = it.state + ("name" to text("Alice"))) }
    private fun numberField(payload: InteractiveUiPayload) = payload.copy(nodes = payload.nodes.map {
        if (it is InteractiveUiNode.NumberInput) it.copy(slider = false) else it
    })

    private fun catalog(): InteractiveUiPayload {
        val graph = parsed("knowledge-map.en").nodes.filterIsInstance<InteractiveUiNode.Graph>().first()
            .copy(id = "graph", visibleWhen = null, bindSelected = null)
        val chart = parsed("chart-bar").nodes.filterIsInstance<InteractiveUiNode.Chart>().single().copy(id = "chart")
        val table = InteractiveUiNode.Table(
            "table",
            null,
            null,
            null,
            listOf(InteractiveUiNode.Table.Column("a", "A", false, false), InteractiveUiNode.Table.Column("b", "B", false, false)),
            emptyList(),
            true
        )
        val carousel = parsed("product-carousel").nodes.filterIsInstance<InteractiveUiNode.Carousel>().single()
        val form = InteractiveUiNode.Form("form", null, null, emptyList(), "Send form", "formAction", true)
        return augment(controlsReady(), listOf(
            graph,
            chart,
            table,
            table.copy(id = "unsortable", sortable = false),
            carousel,
            form,
            InteractiveUiNode.Collapsible("local", null, null, "Details", emptyList(), null),
            InteractiveUiNode.Link("link", null, null, "Website", "https://example.org/", InteractiveUiNode.LinkKind.External)
        ))
    }

    private fun augment(payload: InteractiveUiPayload, nodes: List<InteractiveUiNode>): InteractiveUiPayload {
        val root = payload.nodesById.getValue(payload.root) as InteractiveUiNode.Stack
        return payload.copy(nodes =
            payload.nodes.map { if (it.id == root.id) root.copy(children = root.children + nodes.map { node -> node.id }) else it } + nodes)
    }

    private fun mutateEveryControl(session: InteractiveUiSession) {
        session.setValue("text_input", text("changed"))
        session.setValue("local", InteractiveUiValue.Bool(true))
        session.selectGraphNode("graph", "sugar")
        session.setGraphListMode("graph", true)
        session.setChartDataMode("chart", true)
        session.sortTable("table", "a")
        session.setCarouselIndex("carousel", 1)
        session.activate("toggleButton")
        session.activate("submit")
        session.activateCarouselItem("carousel", "item1")
        session.submitForm("form")
        session.openLink("link")
    }

    private fun condition(key: String, value: InteractiveUiValue) = InteractiveUiCondition(key, InteractiveUiCondition.Comparison.EqualTo(value), false)
    private fun text(value: String) = InteractiveUiValue.Text(value)
    private fun number(value: Int) = InteractiveUiValue.Number(value.toDouble())
    private fun json(value: String): JsonElement = Json.parseToJsonElement(value)
}
