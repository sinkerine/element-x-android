/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.messages.impl.timeline.components.event

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.interactiveui.interactiveUiContent
import io.element.android.features.messages.impl.timeline.di.LocalTimelineItemPresenterFactories
import io.element.android.features.messages.impl.timeline.di.TimelineItemPresenterFactories
import io.element.android.features.messages.impl.timeline.di.TimelineItemPresenterFactory
import io.element.android.features.messages.impl.timeline.model.event.InteractiveUiTimelineModel
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemInteractiveUiContent
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiErrorCode
import io.element.android.libraries.interactiveui.core.session.InteractiveUiPhase
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiRenderNode
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiViewEvent
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiViewState
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.setSafeContent
import org.junit.Test

class TimelineItemInteractiveUiViewTest : RobolectricTest() {
    @Test
    fun `valid card and show as text invoke timeline fallback slot`() = runAndroidComposeUiTest {
        var fallbackCalls = 0
        val model = mutableStateOf(InteractiveUiViewState(
            "Card",
            InteractiveUiPhase.Active,
            null,
            "Sender",
            false,
            false,
            null,
            InteractiveUiRenderNode.Unknown("root", "Card content"),
            {}
        ))
        model.value =
            model.value.copy(eventSink = { if (it is InteractiveUiViewEvent.SetShowAsText) model.value = model.value.copy(showAsText = it.showAsText) })
        val factories = TimelineItemPresenterFactories(mapOf(
            TimelineItemInteractiveUiContent::class to
            TimelineItemPresenterFactory<TimelineItemInteractiveUiContent, InteractiveUiViewState> { Presenter { model.value } }
        ))
        setSafeContent {
            ElementPreview {
                CompositionLocalProvider(LocalTimelineItemPresenterFactories provides factories) {
                    TimelineItemInteractiveUiView(interactiveUiContent(), false, {}, {}, {}, {}, fallbackContent = { fallbackCalls++ })
                }
            }
        }
        onNodeWithText("Card content").assertIsDisplayed()
        assertThat(fallbackCalls).isEqualTo(0)
        onNodeWithText("Show as text").performClick()
        waitForIdle()
        assertThat(fallbackCalls).isGreaterThan(0)
        onNodeWithText("Card content").assertDoesNotExist()
    }

    @Test
    fun `hidden media reveal calls timeline protection callback`() = runAndroidComposeUiTest {
        var reveals = 0
        val state = InteractiveUiViewState(
            "Media",
            InteractiveUiPhase.Active,
            null,
            "Sender",
            false,
            false,
            null,
            InteractiveUiRenderNode.Image("image", "Protected image", null, "image/png", 1f, false),
            {}
        )
        val factories = TimelineItemPresenterFactories(mapOf(
            TimelineItemInteractiveUiContent::class to
            TimelineItemPresenterFactory<TimelineItemInteractiveUiContent, InteractiveUiViewState> { Presenter { state } }
        ))
        setSafeContent {
            ElementPreview {
            CompositionLocalProvider(LocalTimelineItemPresenterFactories provides factories) {
                TimelineItemInteractiveUiView(interactiveUiContent(), true, { reveals++ }, {}, {}, {})
            }
        }
        }
        onNodeWithText("Protected image").assertIsDisplayed()
        onNodeWithText("Show").performClick()
        assertThat(reveals).isEqualTo(1)
    }

    @Test
    fun `invalid shows caption and unsupported version only shows fallback`() = runAndroidComposeUiTest {
        val content = mutableStateOf(interactiveUiContent().copy(model = InteractiveUiTimelineModel.Invalid(InteractiveUiErrorCode.BadType)))
        var fallbacks = 0
        setSafeContent {
            ElementPreview {
            TimelineItemInteractiveUiView(content.value, false, {}, {}, {}, {}, fallbackContent = { fallbacks++ })
        }
        }
        onNodeWithText("Couldn't show interactive content.").assertIsDisplayed()
        assertThat(fallbacks).isGreaterThan(0)
        runOnIdle { content.value = content.value.copy(model = InteractiveUiTimelineModel.Invalid(InteractiveUiErrorCode.BadVersion)) }
        onNodeWithText("Couldn't show interactive content.").assertDoesNotExist()
    }
}
