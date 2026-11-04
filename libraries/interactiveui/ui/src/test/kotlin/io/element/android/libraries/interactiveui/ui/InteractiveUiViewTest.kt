/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.libraries.interactiveui.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiNode
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiOption
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiValue
import io.element.android.libraries.interactiveui.core.session.InteractiveUiNotice
import io.element.android.libraries.interactiveui.core.session.InteractiveUiPhase
import io.element.android.libraries.interactiveui.core.session.InteractiveUiUrlRequest
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiRenderNode
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiViewEvent
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiViewState
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.setSafeContent
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test

internal fun viewState(root: InteractiveUiRenderNode, sink: (InteractiveUiViewEvent) -> Unit = {}) = InteractiveUiViewState(
    "Interactive message",
    InteractiveUiPhase.Active,
    null,
    "Alice",
    false,
    false,
    null,
    root,
    sink,
)

internal val button = InteractiveUiRenderNode.Button("button", "Respond", InteractiveUiNode.ButtonVariant.Primary, true, false, false)

class InteractiveUiViewTest : RobolectricTest() {
    @Test
    fun `button and form emit only local events`() = runAndroidComposeUiTest {
        val events = mutableListOf<InteractiveUiViewEvent>()
        val root = InteractiveUiRenderNode.Form("form", "Submit", persistentListOf(button), true, false)
        setSafeContent { ElementPreview { InteractiveUiView(viewState(root, events::add), {}, fallback = {}) } }
        onNodeWithText("Respond").performClick()
        onNodeWithText("Submit").performClick()
        assertThat(events).containsExactly(InteractiveUiViewEvent.Activate("button"), InteractiveUiViewEvent.SubmitForm("form")).inOrder()
    }

    @Test
    fun `inactive and pending forms disable descendants and submit`() = runAndroidComposeUiTest {
        val events = mutableListOf<InteractiveUiViewEvent>()
        val state = mutableStateOf(viewState(InteractiveUiRenderNode.Form("form", "Submit", persistentListOf(button), true, true), events::add))
        setSafeContent { ElementPreview { InteractiveUiView(state.value, {}, fallback = {}) } }
        onNodeWithText("Respond").assertIsNotEnabled()
        onNodeWithText("Submit").assertIsNotEnabled()
        runOnIdle { state.value = viewState(button, events::add).copy(phase = InteractiveUiPhase.Expired) }
        onNodeWithText("Respond").assertIsNotEnabled()
        assertThat(events).isEmpty()
    }

    @Test
    fun `unknown content and show as text respect caller state`() = runAndroidComposeUiTest {
        val events = mutableListOf<InteractiveUiViewEvent>()
        val state = mutableStateOf(viewState(InteractiveUiRenderNode.Unknown("unknown", "Unsupported custom node"), events::add))
        setSafeContent { ElementPreview { InteractiveUiView(state.value, {}, fallback = { Text("Original event body") }) } }
        onNodeWithText("Unsupported custom node").assertIsDisplayed()
        onNodeWithText("Show as text").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).performClick()
        assertThat(events).containsExactly(InteractiveUiViewEvent.SetShowAsText(true))
        runOnIdle { state.value = state.value.copy(showAsText = true) }
        onNodeWithText("Original event body").assertIsDisplayed()
        onNodeWithText("Unsupported custom node").assertDoesNotExist()
        runOnIdle { state.value = state.value.copy(showAsText = false, root = InteractiveUiRenderNode.Unknown("unknown", null)) }
        onNodeWithText("This content cannot be displayed.").assertIsDisplayed()
    }

    @Test
    fun `overdue status announces sender and retry only emits retry`() = runAndroidComposeUiTest {
        val events = mutableListOf<InteractiveUiViewEvent>()
        val state = viewState(button, events::add).copy(
            phase = InteractiveUiPhase.AwaitingConfirmation,
            notice = InteractiveUiNotice.WaitingForSender,
            confirmationOverdue = true,
        )
        setSafeContent { ElementPreview { InteractiveUiView(state, {}, fallback = {}) } }
        onNodeWithText("No confirmation from Alice yet").assertIsDisplayed()
        onNodeWithText("Try again").performClick()
        assertThat(events).containsExactly(InteractiveUiViewEvent.RetryAfterOverdue)
    }

    @Test
    fun `URL dialog shows full URL cancel opens nothing and approved request opens once`() = runAndroidComposeUiTest {
        val events = mutableListOf<InteractiveUiViewEvent>()
        val opened = mutableListOf<String>()
        val url = "https://example.org/full/path?query=value#fragment"
        val state = mutableStateOf(viewState(button, events::add).copy(urlRequest = InteractiveUiUrlRequest.Confirming(url)))
        setSafeContent { ElementPreview { InteractiveUiView(state.value, opened::add, fallback = {}) } }
        onNodeWithText(url).assertIsDisplayed()
        assertThat(opened).isEmpty()
        onNodeWithText("Cancel").performClick()
        assertThat(events).containsExactly(InteractiveUiViewEvent.ClearUrlRequest)
        assertThat(opened).isEmpty()
        runOnIdle { state.value = state.value.copy(urlRequest = null) }
        runOnIdle { state.value = state.value.copy(urlRequest = InteractiveUiUrlRequest.Confirming(url)) }
        onNodeWithText("Continue").performClick()
        assertThat(events.last()).isEqualTo(InteractiveUiViewEvent.ConfirmOpenUrl)
        assertThat(opened).isEmpty()
        runOnIdle { state.value = state.value.copy(urlRequest = InteractiveUiUrlRequest.Approved(url)) }
        waitForIdle()
        assertThat(opened).containsExactly(url)
        runOnIdle { state.value = state.value.copy(title = "Updated title") }
        assertThat(opened).containsExactly(url)
    }

    @Test
    fun `inactive URL request is cleared without opening`() = runAndroidComposeUiTest {
        val events = mutableListOf<InteractiveUiViewEvent>()
        val opened = mutableListOf<String>()
        setSafeContent {
            ElementPreview {
                InteractiveUiView(viewState(button, events::add).copy(
                    phase = InteractiveUiPhase.Submitted,
                    urlRequest = InteractiveUiUrlRequest.Approved("https://example.org")
                ), opened::add, fallback = {})
            }
        }
        waitForIdle()
        assertThat(events).containsExactly(InteractiveUiViewEvent.ClearUrlRequest)
        assertThat(opened).isEmpty()
        onNodeWithText("Open link?").assertDoesNotExist()
    }

    @Test
    fun `select choice and toggle have labels roles and exact values`() = runAndroidComposeUiTest {
        val events = mutableListOf<InteractiveUiViewEvent>()
        val options = persistentListOf(InteractiveUiOption("a", "Alpha"), InteractiveUiOption("b", "Beta"))
        val root = InteractiveUiRenderNode.Stack("root", false, 8.dp, persistentListOf(
            InteractiveUiRenderNode.Select(
                "select",
                "Select option",
                null,
                persistentListOf(InteractiveUiOption("a", "Select Alpha"), InteractiveUiOption("b", "Select Beta")),
                true
            ),
            InteractiveUiRenderNode.Choice("choice", "Pick one", persistentListOf(), false, options, true),
            InteractiveUiRenderNode.Choice(
                "multi",
                "Pick several",
                persistentListOf("a"),
                true,
                persistentListOf(InteractiveUiOption("a", "Remove Alpha"), InteractiveUiOption("b", "Add Beta")),
                true
            ),
            InteractiveUiRenderNode.Toggle("toggle", "Notify me", false, true),
        ))
        setSafeContent {
            ElementPreview {
                Column(Modifier.verticalScroll(rememberScrollState())) { InteractiveUiView(viewState(root, events::add), {}, fallback = {}) }
            }
        }
        onNodeWithContentDescription("Select option, Choose an option").performClick()
        onNodeWithText("Select Beta").performClick()
        onNodeWithText("Alpha").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).performClick()
        onNodeWithText("Remove Alpha").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).performClick()
        onNodeWithText("Add Beta").performScrollTo().performClick()
        onNodeWithText("Notify me").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).performClick()
        assertThat(events).containsExactly(
            InteractiveUiViewEvent.SetValue("select", InteractiveUiValue.Text("b")),
            InteractiveUiViewEvent.SetValue("choice", InteractiveUiValue.Text("a")),
            InteractiveUiViewEvent.SetValue("multi", InteractiveUiValue.TextList(emptyList())),
            InteractiveUiViewEvent.SetValue("multi", InteractiveUiValue.TextList(listOf("a", "b"))),
            InteractiveUiViewEvent.SetValue("toggle", InteractiveUiValue.Bool(true)),
        ).inOrder()
    }

    @Test
    fun `tabs collapsible and link emit their node events`() = runAndroidComposeUiTest {
        val events = mutableListOf<InteractiveUiViewEvent>()
        val panel = InteractiveUiRenderNode.Unknown("panel", "Panel content")
        val root = InteractiveUiRenderNode.Stack("root", false, 8.dp, persistentListOf(
            InteractiveUiRenderNode.Tabs(
                "tabs",
                "a",
                persistentListOf(InteractiveUiRenderNode.Tabs.Tab("a", "Overview", panel), InteractiveUiRenderNode.Tabs.Tab("b", "Details", panel)),
                true
            ),
            InteractiveUiRenderNode.Collapsible("collapse", "Expand", false, true, persistentListOf(panel)),
            InteractiveUiRenderNode.Link("link", "Reference", "example.org", true, true),
        ))
        setSafeContent { ElementPreview { InteractiveUiView(viewState(root, events::add), {}, fallback = {}) } }
        onNodeWithText("Details").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
        onNodeWithText("Expand +").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed")).performClick()
        onNodeWithText("Reference · example.org (Source)").performClick()
        assertThat(events).containsExactly(
            InteractiveUiViewEvent.SetValue("tabs", InteractiveUiValue.Text("b")),
            InteractiveUiViewEvent.SetValue("collapse", InteractiveUiValue.Bool(true)),
            InteractiveUiViewEvent.OpenLink("link"),
        ).inOrder()
    }

    @Test
    fun `disabled tabs disable nested enabled collapsible controls and suppress interaction`() = runAndroidComposeUiTest {
        val events = mutableListOf<InteractiveUiViewEvent>()
        val child = InteractiveUiRenderNode.Collapsible("inner", "Nested section", true, true, persistentListOf(
            button,
            InteractiveUiRenderNode.Toggle("toggle", "Notify me", false, true),
        ))
        val tabs = InteractiveUiRenderNode.Tabs(
            "tabs",
            "a",
            persistentListOf(InteractiveUiRenderNode.Tabs.Tab("a", "Overview", child)),
            false,
        )
        val state = mutableStateOf(viewState(tabs, events::add))
        setSafeContent { ElementPreview { InteractiveUiView(state.value, {}, fallback = {}) } }
        listOf("Overview", "Nested section −", "Respond", "Notify me").forEach { label ->
            onNodeWithText(label).assertIsNotEnabled().performClick()
        }
        assertThat(events).isEmpty()
        runOnIdle { state.value = state.value.copy(root = tabs.copy(enabled = true)) }
        onNodeWithText("Respond").performClick()
        onNodeWithText("Notify me").performClick()
        assertThat(events).containsExactly(
            InteractiveUiViewEvent.Activate("button"),
            InteractiveUiViewEvent.SetValue("toggle", InteractiveUiValue.Bool(true)),
        ).inOrder()
        runOnIdle { state.value = state.value.copy(phase = InteractiveUiPhase.Expired) }
        onNodeWithText("Respond").assertIsNotEnabled().performClick()
        onNodeWithText("Notify me").assertIsNotEnabled().performClick()
        assertThat(events).hasSize(2)
    }

    @Test
    fun `disabled open collapsible disables nested enabled tabs and suppresses interaction`() = runAndroidComposeUiTest {
        val events = mutableListOf<InteractiveUiViewEvent>()
        val child = InteractiveUiRenderNode.Tabs(
            "tabs",
            "a",
            persistentListOf(InteractiveUiRenderNode.Tabs.Tab("a", "Overview", button)),
            true,
        )
        val collapsible = InteractiveUiRenderNode.Collapsible("collapse", "Section", true, false, persistentListOf(child))
        val state = mutableStateOf(viewState(collapsible, events::add))
        setSafeContent { ElementPreview { InteractiveUiView(state.value, {}, fallback = {}) } }
        listOf("Section −", "Overview", "Respond").forEach { label ->
            onNodeWithText(label).assertIsNotEnabled().performClick()
        }
        assertThat(events).isEmpty()
        runOnIdle { state.value = state.value.copy(root = collapsible.copy(enabled = true)) }
        onNodeWithText("Respond").performClick()
        assertThat(events).containsExactly(InteractiveUiViewEvent.Activate("button"))
        runOnIdle { state.value = state.value.copy(phase = InteractiveUiPhase.Expired) }
        onNodeWithText("Respond").assertIsNotEnabled().performClick()
        assertThat(events).hasSize(1)
    }

    @Test
    fun `horizontal cards wrap within narrow width`() = runAndroidComposeUiTest {
        val root = InteractiveUiRenderNode.Stack("root", true, 8.dp, persistentListOf(
            InteractiveUiRenderNode.Card("a", "First card", persistentListOf()),
            InteractiveUiRenderNode.Card("b", "Second card", persistentListOf()),
        ))
        setSafeContent { ElementPreview { Box(Modifier.width(360.dp)) { InteractiveUiView(viewState(root), {}, fallback = {}) } } }
        val first = onNodeWithText("First card").fetchSemanticsNode().boundsInRoot
        val second = onNodeWithText("Second card").fetchSemanticsNode().boundsInRoot
        assertThat(second.top).isAtLeast(first.bottom)
        assertThat(second.right).isAtMost(first.right + 1f)
    }

    @Test
    fun `static catalog and media fallback render with protected show callback`() = runAndroidComposeUiTest {
        var reveals = 0
        val content = InteractiveUiViewStatePreviewParam().values.elementAt(1)
        setSafeContent {
            ElementPreview {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    InteractiveUiView(content, {}, fallback = {}, onShowMedia = { reveals++ })
                }
            }
        }
        onNodeWithText("A heading").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        onNodeWithText("Sanitised rich text").assertIsDisplayed()
        onNodeWithText("Delivery").performScrollTo().assertIsDisplayed()
        onNodeWithText("1. First").performScrollTo().assertIsDisplayed()
        onNodeWithText("Protected photograph").performScrollTo().assertIsDisplayed()
        onNodeWithText("Show").performScrollTo().performClick()
        assertThat(reveals).isEqualTo(1)
    }
}
