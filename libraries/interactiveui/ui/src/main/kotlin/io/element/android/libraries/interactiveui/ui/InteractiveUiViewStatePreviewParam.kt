/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.ui

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiNode
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiOption
import io.element.android.libraries.interactiveui.core.session.InteractiveUiNotice
import io.element.android.libraries.interactiveui.core.session.InteractiveUiPhase
import io.element.android.libraries.interactiveui.core.session.InteractiveUiUrlRequest
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiBadgeTone
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiRenderNode
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiViewState
import kotlinx.collections.immutable.persistentListOf

internal class InteractiveUiViewStatePreviewParam : PreviewParameterProvider<InteractiveUiViewState> {
    private val options = persistentListOf(InteractiveUiOption("one", "First option"), InteractiveUiOption("two", "Second option"))
    private val text = InteractiveUiRenderNode.Text("text", "Native interactive content", InteractiveUiNode.TextTone.Default, false)
    private val content = InteractiveUiRenderNode.Stack("content", false, 8.dp, persistentListOf(
        InteractiveUiRenderNode.Heading("heading", "A heading", 1),
        text,
        InteractiveUiRenderNode.RichText("rich", "Sanitised rich text", null),
        InteractiveUiRenderNode.Badge("badge", "Ready", InteractiveUiBadgeTone.Success),
        InteractiveUiRenderNode.KeyValue("kv", persistentListOf("Delivery" to "Tomorrow")),
        InteractiveUiRenderNode.ItemList("list", true, persistentListOf("First" to "Description", "Second" to null)),
        InteractiveUiRenderNode.Divider("divider"),
        InteractiveUiRenderNode.Image("image", "Protected photograph", null, "image/png", null, true),
        InteractiveUiRenderNode.Link("link", "Read more", "example.org", true, true),
        InteractiveUiRenderNode.Unknown("unknown", null),
    ))
    private val controls = InteractiveUiRenderNode.Form("form", "Send response", persistentListOf(
        InteractiveUiRenderNode.Select("select", "Delivery method", "one", options, true),
        InteractiveUiRenderNode.Choice("choice", "Choose one", persistentListOf("one"), false, options, true),
        InteractiveUiRenderNode.Choice("multi", "Choose several", persistentListOf("two"), true, options, true),
        InteractiveUiRenderNode.Toggle("toggle", "Send notifications", true, true),
        InteractiveUiRenderNode.TextInput("input", "Name", "", false, "Your name", 100, true, false, true),
        InteractiveUiRenderNode.NumberInput("number", "Amount", 12.5, 0.0, 100.0, 0.5, false, false, true),
        InteractiveUiRenderNode.NumberInput("slider", "Rating", 5.0, 0.0, 10.0, 1.0, true, false, true),
        InteractiveUiRenderNode.Button("button", "Open website", InteractiveUiNode.ButtonVariant.Secondary, true, false, true),
    ), true, false)
    private val layout = InteractiveUiRenderNode.Stack("layout", false, 8.dp, persistentListOf(
        InteractiveUiRenderNode.Tabs("tabs", "one", persistentListOf(InteractiveUiRenderNode.Tabs.Tab("one", "Overview", text)), true),
        InteractiveUiRenderNode.Collapsible("collapse", "Details", true, true, persistentListOf(text)),
        InteractiveUiRenderNode.Stack("row", true, 8.dp, persistentListOf(
            InteractiveUiRenderNode.Card("card1", "First card", persistentListOf(text)),
            InteractiveUiRenderNode.Card("card2", "Second card", persistentListOf(text)),
        )),
    ))
    private val carousel = InteractiveUiRenderNode.Carousel("carousel", "Available items", 0, persistentListOf(
        InteractiveUiRenderNode.Carousel.Item(
            "first",
            null,
            "First photograph",
            "First item",
            "Description",
            persistentListOf("Metadata"),
            "New" to InteractiveUiBadgeTone.Info,
            "Choose",
            false,
            true
        ),
        InteractiveUiRenderNode.Carousel.Item("second", null, "Second photograph", "Second item", null, persistentListOf(), null, null, false, false),
    ), true)
    private val active = InteractiveUiViewState("Interactive message", InteractiveUiPhase.Active, null, "Alice", false, false, null, controls, {})
    override val values = sequenceOf(
        active,
        active.copy(root = content),
        active.copy(root = layout),
        active.copy(root = carousel),
        active.copy(root = carousel.copy(items = persistentListOf(carousel.items.first()))),
        active.copy(phase = InteractiveUiPhase.Sending, root = controls.copy(pending = true)),
        active.copy(phase = InteractiveUiPhase.AwaitingConfirmation),
        active.copy(phase = InteractiveUiPhase.AwaitingConfirmation, confirmationOverdue = true, notice = InteractiveUiNotice.WaitingForSender),
        active.copy(phase = InteractiveUiPhase.Submitted, notice = InteractiveUiNotice.PayloadText("Thank you for your response")),
        active.copy(phase = InteractiveUiPhase.Expired),
        active.copy(phase = InteractiveUiPhase.Superseded),
        active.copy(notice = InteractiveUiNotice.SendFailed),
        active.copy(showAsText = true),
        active.copy(root = InteractiveUiRenderNode.TextInput("invalid", "Required name", "", false, null, 100, true, true, true)),
        active.copy(root = InteractiveUiRenderNode.NumberInput("invalid", "Amount", null, 0.0, 10.0, null, false, true, true)),
        active.copy(urlRequest = InteractiveUiUrlRequest.Confirming("https://example.org/full/path?query=value")),
    )
}

@Preview(widthDp = 360, fontScale = 2f)
@Composable
internal fun InteractiveUiLargeFontPreview() = ElementPreview {
    InteractiveUiView(InteractiveUiViewStatePreviewParam().values.first(), {}, fallback = { Text("Original message text") })
}

@PreviewsDayNight
@Composable
internal fun InteractiveUiViewPreview(@PreviewParameter(InteractiveUiViewStatePreviewParam::class) state: InteractiveUiViewState) = ElementPreview {
    InteractiveUiView(state, {}, fallback = { Text("Original message text") }, modifier = Modifier.width(360.dp))
}
