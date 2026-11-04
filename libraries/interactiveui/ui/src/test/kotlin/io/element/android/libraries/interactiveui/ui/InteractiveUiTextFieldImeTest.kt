/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.libraries.interactiveui.ui

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiValue
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiRenderNode
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiViewEvent
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.setSafeContent
import org.junit.Test

class InteractiveUiTextFieldImeTest : RobolectricTest() {
    private val input = InteractiveUiRenderNode.TextInput("text", "Name", "", false, "Enter name", 3, true, false, true)

    @Test
    fun `IME next and done never submit`() = runAndroidComposeUiTest {
        val events = mutableListOf<InteractiveUiViewEvent>()
        setSafeContent { ElementPreview { InteractiveUiView(viewState(input, events::add), {}, {}) } }
        val field = onNodeWithContentDescription("Name")
        field.performTextReplacement("Ada")
        field.performImeAction()
        field.performSemanticsAction(SemanticsActions.OnImeAction) { it() }
        assertThat(events).containsExactly(InteractiveUiViewEvent.SetValue("text", InteractiveUiValue.Text("Ada")))
    }

    @Test
    fun `IME composing text survives caller echo and never submits`() = runAndroidComposeUiTest {
        val events = mutableListOf<InteractiveUiViewEvent>()
        val model = mutableStateOf(input.copy(maxLength = 100))
        setSafeContent {
            ElementPreview {
                InteractiveUiView(viewState(model.value) { event ->
                    events.add(event)
                    if (event is InteractiveUiViewEvent.SetValue) model.value = model.value.copy(value = (event.value as InteractiveUiValue.Text).value)
                }, {}, {})
            }
        }
        onNodeWithContentDescription("Name").performClick()
        waitForIdle()
        val connection = runOnIdle {
            fun findEditor(view: View): View? {
                if (view.onCheckIsTextEditor()) return view
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) findEditor(view.getChildAt(index))?.let { return it }
                }
                return null
            }
            checkNotNull(findEditor(checkNotNull(activity).window.decorView)?.onCreateInputConnection(EditorInfo()))
        }
        runOnIdle { connection.setComposingText("に", 1) }
        waitForIdle()
        onNodeWithContentDescription("Name").assertTextContains("に")
        runOnIdle { model.value = model.value.copy(invalid = true) }
        runOnIdle { connection.setComposingText("日本", 1) }
        waitForIdle()
        onNodeWithContentDescription("Name").assertTextContains("日本")
        runOnIdle { connection.finishComposingText() }
        assertThat(events.filterIsInstance<InteractiveUiViewEvent.SubmitForm>()).isEmpty()
        assertThat(events.filterIsInstance<InteractiveUiViewEvent.SetValue>().last().value).isEqualTo(InteractiveUiValue.Text("日本"))
    }

    @Test
    fun `external text can return to an acknowledged emission repeatedly`() = runAndroidComposeUiTest {
        val events = mutableListOf<InteractiveUiViewEvent>()
        val model = mutableStateOf(input.copy(value = "A"))
        setSafeContent {
            ElementPreview {
                InteractiveUiView(viewState(model.value) { event ->
                    events.add(event)
                    if (event is InteractiveUiViewEvent.SetValue) model.value = model.value.copy(value = (event.value as InteractiveUiValue.Text).value)
                }, {}, {})
            }
        }
        val field = onNodeWithContentDescription("Name")
        field.performTextReplacement("B")
        runOnIdle { assertThat(model.value.value).isEqualTo("B") }
        listOf("C", "B", "C", "B", "", "B", "A").forEach { value ->
            runOnIdle { model.value = model.value.copy(value = value) }
            field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))
        }
        assertThat(events).containsExactly(InteractiveUiViewEvent.SetValue("text", InteractiveUiValue.Text("B")))
    }

    @Test
    fun `external replacement invalidates an unacknowledged text emission`() = runAndroidComposeUiTest {
        val model = mutableStateOf(input.copy(value = "A"))
        setSafeContent { ElementPreview { InteractiveUiView(viewState(model.value), {}, {}) } }
        val field = onNodeWithContentDescription("Name")
        field.performTextReplacement("B")
        runOnIdle { model.value = model.value.copy(value = "C") }
        field.assertTextContains("C")
        runOnIdle { model.value = model.value.copy(value = "B") }
        field.assertTextContains("B")
        field.performTextReplacement("")
        runOnIdle { model.value = model.value.copy(value = "A") }
        field.assertTextContains("A")
        runOnIdle { model.value = model.value.copy(value = "") }
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test
    fun `delayed and unchanged text echoes retain selection after external replacement`() = runAndroidComposeUiTest {
        val model = mutableStateOf(input.copy(value = "A"))
        setSafeContent { ElementPreview { InteractiveUiView(viewState(model.value), {}, {}) } }
        val field = onNodeWithContentDescription("Name")
        runOnIdle { model.value = model.value.copy(value = "C") }
        field.assertTextContains("C")
        field.performTextReplacement("Ada")
        field.performSemanticsAction(SemanticsActions.SetSelection) { it(1, 2, false) }
        runOnIdle { model.value = model.value.copy(value = "Ada") }
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(1, 2)))
        runOnIdle { model.value = model.value.copy(invalid = true) }
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(1, 2)))
        runOnIdle { model.value = model.value.copy(value = "C") }
        field.assertTextContains("C")
        runOnIdle { model.value = model.value.copy(value = "Ada") }
        field.assertTextContains("Ada")
    }

    @Test
    fun `maximum counts Unicode code points and rejects extra characters without resetting`() = runAndroidComposeUiTest {
        val events = mutableListOf<InteractiveUiViewEvent>()
        val model = mutableStateOf(input)
        setSafeContent {
            ElementPreview {
                InteractiveUiView(viewState(model.value) { event ->
                    events.add(event)
                    if (event is InteractiveUiViewEvent.SetValue) model.value = model.value.copy(value = (event.value as InteractiveUiValue.Text).value)
                }, {}, {})
            }
        }
        val field = onNodeWithContentDescription("Name")
        field.performTextReplacement("😀ab")
        field.assertTextContains("😀ab")
        field.performTextReplacement("😀abc")
        field.assertTextContains("😀ab")
        assertThat(events).containsExactly(InteractiveUiViewEvent.SetValue("text", InteractiveUiValue.Text("😀ab")))
        runOnIdle { model.value = model.value.copy(invalid = true) }
        field.assertTextContains("😀ab")
    }
}
