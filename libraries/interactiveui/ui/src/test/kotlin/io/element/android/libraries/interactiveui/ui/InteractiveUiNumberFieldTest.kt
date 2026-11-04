/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.libraries.interactiveui.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiNode
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiPayload
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiStatus
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiValue
import io.element.android.libraries.interactiveui.core.state.InteractiveUiStateRules
import io.element.android.libraries.interactiveui.ui.components.numberDraftValue
import io.element.android.libraries.interactiveui.ui.components.sliderSteps
import io.element.android.libraries.interactiveui.ui.components.sliderValue
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiRenderNode
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiViewEvent
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.setSafeContent
import org.junit.Test

class InteractiveUiNumberFieldTest : RobolectricTest() {
    private val node = InteractiveUiRenderNode.NumberInput("number", "Amount", null, 0.0, 100.0, 0.5, false, false, true)

    @Test
    fun `raw partial and comma drafts survive validation and unrelated recomposition`() = runAndroidComposeUiTest {
        val events = mutableListOf<InteractiveUiViewEvent>()
        val model = mutableStateOf(node)
        val title = mutableStateOf("Original title")
        setSafeContent {
            ElementPreview {
                InteractiveUiView(viewState(model.value) { event ->
                    events.add(event)
                    if (event is InteractiveUiViewEvent.SetValue) {
                        val value = (event.value as? InteractiveUiValue.Number)?.value
                        // Mirrors the session contract: invalid drafts do not replace the accepted value.
                        if (value != null && value in 0.0..100.0) model.value = model.value.copy(value = value)
                    }
                }.copy(title = title.value), {}, fallback = {})
            }
        }
        val field = onNodeWithContentDescription("Amount")
        field.performTextReplacement("-")
        runOnIdle {
            model.value = model.value.copy(invalid = true)
            title.value = "Recomposed"
        }
        field.assertTextContains("-")
        field.performTextReplacement("1.")
        field.assertTextContains("1.")
        field.performTextReplacement("12,5")
        field.assertTextContains("12,5")
        runOnIdle { title.value = "Again" }
        field.assertTextContains("12,5")
        field.performTextReplacement("999")
        runOnIdle { model.value = model.value.copy(invalid = false) }
        field.assertTextContains("999")
        assertThat(events).containsExactly(
            InteractiveUiViewEvent.SetValue("number", InteractiveUiValue.Null),
            InteractiveUiViewEvent.SetValue("number", InteractiveUiValue.Null),
            InteractiveUiViewEvent.SetValue("number", InteractiveUiValue.Number(12.5)),
            InteractiveUiViewEvent.SetValue("number", InteractiveUiValue.Number(999.0)),
        ).inOrder()
    }

    @Test
    fun `external value changes replace draft`() = runAndroidComposeUiTest {
        val model = mutableStateOf(node)
        setSafeContent { ElementPreview { InteractiveUiView(viewState(model.value), {}, fallback = {}) } }
        val field = onNodeWithContentDescription("Amount")
        field.performTextReplacement("-")
        runOnIdle { model.value = model.value.copy(value = 5.0) }
        field.assertTextContains("5")
    }

    @Test
    fun `external numbers can return to an acknowledged emission repeatedly`() = runAndroidComposeUiTest {
        val events = mutableListOf<InteractiveUiViewEvent>()
        val model = mutableStateOf(node.copy(value = 1.0))
        setSafeContent {
            ElementPreview {
                InteractiveUiView(viewState(model.value) { event ->
                    events.add(event)
                    if (event is InteractiveUiViewEvent.SetValue) model.value = model.value.copy(value = (event.value as? InteractiveUiValue.Number)?.value)
                }, {}, fallback = {})
            }
        }
        val field = onNodeWithContentDescription("Amount")
        field.performTextReplacement("2")
        runOnIdle { assertThat(model.value.value).isEqualTo(2.0) }
        listOf(3.0 to "3", 2.0 to "2", 3.0 to "3", 2.0 to "2", null to "", 0.0 to "0", 2.0 to "2").forEach { (value, text) ->
            runOnIdle { model.value = model.value.copy(value = value) }
            field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text)))
        }
        assertThat(events).containsExactly(InteractiveUiViewEvent.SetValue("number", InteractiveUiValue.Number(2.0)))
    }

    @Test
    fun `external replacement invalidates unacknowledged number null and zero emissions`() = runAndroidComposeUiTest {
        val model = mutableStateOf(node.copy(value = 1.0))
        setSafeContent { ElementPreview { InteractiveUiView(viewState(model.value), {}, fallback = {}) } }
        val field = onNodeWithContentDescription("Amount")
        listOf("2" to 2.0, "-" to null, "0,00" to 0.0).forEach { (draft, value) ->
            field.performTextReplacement(draft)
            runOnIdle { model.value = model.value.copy(value = 3.0) }
            field.assertTextContains("3")
            runOnIdle { model.value = model.value.copy(value = value) }
            val text = value?.toInt()?.toString().orEmpty()
            field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text)))
        }
    }

    @Test
    fun `delayed and unchanged number echoes retain raw decimal and selection after external replacement`() = runAndroidComposeUiTest {
        val model = mutableStateOf(node.copy(value = 1.0))
        setSafeContent { ElementPreview { InteractiveUiView(viewState(model.value), {}, fallback = {}) } }
        val field = onNodeWithContentDescription("Amount")
        runOnIdle { model.value = model.value.copy(value = 3.0) }
        field.assertTextContains("3")
        listOf("12,50" to 12.5, "0,00" to 0.0, "-" to null, "1." to null).forEach { (draft, value) ->
            field.performTextReplacement(draft)
            field.performSemanticsAction(SemanticsActions.SetSelection) { it(1, 1, false) }
            runOnIdle { model.value = model.value.copy(value = value) }
            field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(draft)))
            field.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(1)))
            runOnIdle { model.value = model.value.copy(invalid = !model.value.invalid) }
            field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(draft)))
            field.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(1)))
        }
        runOnIdle { model.value = model.value.copy(value = 3.0) }
        field.assertTextContains("3")
        runOnIdle { model.value = model.value.copy(value = null) }
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test
    fun `number parser never emits nonfinite values and accepts both decimal separators`() {
        listOf("-", "1.", "", "Infinity", "NaN", "1e999", ",").forEach { assertThat(numberDraftValue(it)).isEqualTo(InteractiveUiValue.Null) }
        assertThat(numberDraftValue("12,5")).isEqualTo(InteractiveUiValue.Number(12.5))
        assertThat(numberDraftValue(",5")).isEqualTo(InteractiveUiValue.Number(0.5))
        assertThat(numberDraftValue("-0.5")).isEqualTo(InteractiveUiValue.Number(-0.5))
    }

    @Test
    fun `discrete slider steps require finite evenly divided bounded ranges`() {
        assertThat(sliderSteps(0.0, 10.0, 1.0)).isEqualTo(9)
        assertThat(sliderSteps(0.0, 1.0, 0.01)).isEqualTo(99)
        assertThat(sliderSteps(0.0, 1.0, 0.001)).isEqualTo(0)
        assertThat(sliderSteps(0.0, 1.0, 0.3)).isEqualTo(0)
        assertThat(sliderSteps(0.0, 0.0, 1.0)).isEqualTo(0)
        assertThat(sliderSteps(0.0, 1.0, Double.NaN)).isEqualTo(0)
        assertThat(sliderSteps(Double.NEGATIVE_INFINITY, 1.0, 1.0)).isEqualTo(0)
        assertThat(sliderSteps(-Double.MAX_VALUE, Double.MAX_VALUE, 1.0)).isEqualTo(0)
    }

    @Test
    fun `slider conversion preserves original decimal endpoints and discrete stops`() {
        assertThat(sliderValue(0.1f, 0.0, 0.1, 0.01)).isEqualTo(0.1)
        assertThat(sliderValue(0.1f, 0.1, 0.2, 0.01)).isEqualTo(0.1)
        assertThat(sliderValue(0.07f, 0.0, 0.1, 0.01)).isEqualTo(7 * 0.01)
        assertThat(sliderValue(-0.03f, -0.1, 0.0, 0.01)).isEqualTo(-0.1 + 7 * 0.01)
        assertThat(sliderValue(0.2f, 0.1, 0.2, 0.1)).isEqualTo(0.2)
        assertThat(sliderValue(-1f, 0.1, 0.2, null)).isEqualTo(0.1)
        assertThat(sliderValue(1f, 0.1, 0.2, null)).isEqualTo(0.2)
    }

    @Test
    fun `continuous slider conversion retains small and fractional interior precision`() {
        listOf(0.12345678f, 0.00000012345678f).forEach { value ->
            assertThat(sliderValue(value, 0.0, 1.0, null)).isEqualTo(value.toDouble())
            assertThat(sliderValue(value, 0.0, 1.0, 0.3)).isEqualTo(value.toDouble())
            assertThat(sliderValue(value, 0.0, 1.0, 0.001)).isEqualTo(value.toDouble())
        }
        assertThat(sliderValue(1e38f, -3e38, 3e38, null)).isEqualTo(1e38f.toDouble())
        assertThat(sliderValue(1e-38f, 0.0, 3e-38, null)).isEqualTo(1e-38f.toDouble())
        assertThat(sliderValue(Float.MAX_VALUE, 0.0, Float.MAX_VALUE.toDouble(), null)).isEqualTo(Float.MAX_VALUE.toDouble())
    }

    @Test
    fun `slider conversion rejects invalid ranges and nonfinite progress`() {
        assertThat(sliderValue(0f, 1.0, 0.0, null)).isNull()
        assertThat(sliderValue(0f, 0.0, 0.0, null)).isNull()
        assertThat(sliderValue(0f, Double.NaN, 1.0, null)).isNull()
        assertThat(sliderValue(0f, 0.0, Double.POSITIVE_INFINITY, null)).isNull()
        assertThat(sliderValue(Float.NaN, 0.0, 1.0, null)).isNull()
        assertThat(sliderValue(Float.POSITIVE_INFINITY, 0.0, 1.0, null)).isNull()
    }

    @Test
    fun `decimal slider progress emits endpoints and interior values accepted by core rules`() = runAndroidComposeUiTest {
        val events = mutableListOf<InteractiveUiViewEvent>()
        val model = mutableStateOf(node.copy(min = 0.0, max = 0.1, step = 0.01, slider = true, value = 0.0))
        val protocolNode = InteractiveUiNode.NumberInput("number", null, null, "Amount", "amount", 0.0, 0.1, 0.01, true)
        val payload = InteractiveUiPayload(
            "decimal-slider",
            0,
            InteractiveUiStatus.Active,
            null,
            null,
            mapOf("amount" to InteractiveUiValue.Number(0.0)),
            "number",
            listOf(protocolNode),
        )
        setSafeContent {
            ElementPreview {
                InteractiveUiView(viewState(model.value) { event ->
                    events.add(event)
                    if (event is InteractiveUiViewEvent.SetValue) {
                        assertThat(InteractiveUiStateRules.acceptsStateValue(payload, "amount", event.value)).isTrue()
                        model.value = model.value.copy(value = (event.value as InteractiveUiValue.Number).value)
                    }
                }, {}, fallback = {})
            }
        }
        val slider = onNodeWithContentDescription("Amount")
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0.1f) }
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0.07f) }
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        assertThat(events).containsExactly(
            InteractiveUiViewEvent.SetValue("number", InteractiveUiValue.Number(0.1)),
            InteractiveUiViewEvent.SetValue("number", InteractiveUiValue.Number(7 * 0.01)),
            InteractiveUiViewEvent.SetValue("number", InteractiveUiValue.Number(0.0)),
        ).inOrder()
    }

    @Test
    fun `invalid slider displays localized error and exposes error semantics`() = runAndroidComposeUiTest {
        val model = mutableStateOf(node.copy(slider = true, invalid = true))
        setSafeContent { ElementPreview { InteractiveUiView(viewState(model.value), {}, fallback = {}) } }
        onNodeWithText("Enter a valid value").assertIsDisplayed()
        onNodeWithContentDescription("Amount").assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, "Enter a valid value"))
        runOnIdle { model.value = model.value.copy(invalid = false) }
        onNodeWithText("Enter a valid value").assertDoesNotExist()
        onNodeWithContentDescription("Amount").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
    }

    @Test
    fun `inverted constant and extreme slider ranges fall back to number field`() = runAndroidComposeUiTest {
        val events = mutableListOf<InteractiveUiViewEvent>()
        val model = mutableStateOf(node.copy(slider = true))
        setSafeContent { ElementPreview { InteractiveUiView(viewState(model.value, events::add), {}, fallback = {}) } }
        val expected = mutableListOf<InteractiveUiViewEvent>()
        listOf(1.0 to 0.0, 0.1 to 0.1, -3e38 to 3e38, -Double.MAX_VALUE to Double.MAX_VALUE, 0.0 to Double.MIN_VALUE).forEachIndexed { index, (min, max) ->
            runOnIdle { model.value = model.value.copy(min = min, max = max) }
            val value = index + 0.1
            onNodeWithContentDescription("Amount").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText)).performTextReplacement(value.toString())
            expected.add(InteractiveUiViewEvent.SetValue("number", InteractiveUiValue.Number(value)))
        }
        assertThat(events).containsExactlyElementsIn(expected).inOrder()
    }

    @Test
    fun `slider exposes label and emits finite value`() = runAndroidComposeUiTest {
        val events = mutableListOf<InteractiveUiViewEvent>()
        setSafeContent { ElementPreview { InteractiveUiView(viewState(node.copy(slider = true, value = 5.0), events::add), {}, fallback = {}) } }
        onNodeWithContentDescription("Amount").performSemanticsAction(SemanticsActions.SetProgress) { it(20f) }
        assertThat(events).containsExactly(InteractiveUiViewEvent.SetValue("number", InteractiveUiValue.Number(20.0)))
    }
}
