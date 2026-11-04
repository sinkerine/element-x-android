/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.libraries.interactiveui.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiRenderNode
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiViewEvent
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.setSafeContent
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test

class InteractiveUiCarouselTest : RobolectricTest() {
    private fun item(id: String) = InteractiveUiRenderNode.Carousel.Item(
        id,
        null,
        "Photo $id",
        "Item $id",
        null,
        persistentListOf("Metadata"),
        null,
        "Choose $id",
        false,
        false
    )
    private val carousel = InteractiveUiRenderNode.Carousel(
        "carousel",
        "Products",
        0,
        persistentListOf(item("A"), item("B"), item("C")),
        true
    )

    @Test
    fun `next previous and item actions emit exactly once without pager feedback`() = runAndroidComposeUiTest {
        val events = mutableListOf<InteractiveUiViewEvent>()
        val model = mutableStateOf(carousel)
        setSafeContent {
            ElementPreview {
                InteractiveUiView(viewState(model.value) { event ->
                    events.add(event)
                    if (event is InteractiveUiViewEvent.SetCarouselIndex) model.value = model.value.copy(index = event.index)
                }, {}, {})
            }
        }
        onNodeWithText("Item 1 of 3").assertIsDisplayed()
        onNodeWithText("Previous").assertIsNotEnabled()
        onNodeWithText("Next").performClick()
        waitForIdle()
        onNodeWithText("Item 2 of 3").assertIsDisplayed()
        onNodeWithText("Choose B").performClick()
        onNodeWithText("Previous").performClick()
        waitForIdle()
        assertThat(events).containsExactly(
            InteractiveUiViewEvent.SetCarouselIndex("carousel", 1),
            InteractiveUiViewEvent.ActivateCarouselItem("carousel", "B"),
            InteractiveUiViewEvent.SetCarouselIndex("carousel", 0),
        ).inOrder()
    }

    @Test
    fun `settled swipe moves one item and resize keeps selection without extra event`() = runAndroidComposeUiTest {
        val events = mutableListOf<InteractiveUiViewEvent>()
        val model = mutableStateOf(carousel)
        val width = mutableStateOf(360.dp)
        val title = mutableStateOf("Title")
        setSafeContent {
            ElementPreview {
                Box(Modifier.width(width.value)) {
                    InteractiveUiView(viewState(model.value) { event ->
                        events.add(event)
                        if (event is InteractiveUiViewEvent.SetCarouselIndex) model.value = model.value.copy(index = event.index)
                    }.copy(title = title.value), {}, {})
                }
            }
        }
        onNodeWithTag("interactive-ui-carousel-carousel").performTouchInput { swipeLeft() }
        waitForIdle()
        assertThat(events).containsExactly(InteractiveUiViewEvent.SetCarouselIndex("carousel", 1))
        onNodeWithText("Item 2 of 3").assertIsDisplayed()
        runOnIdle {
            width.value = 600.dp
            title.value = "Changed"
        }
        waitForIdle()
        onNodeWithText("Item 2 of 3").assertIsDisplayed()
        onNodeWithText("Item B").assertIsDisplayed()
        assertThat(events).containsExactly(InteractiveUiViewEvent.SetCarouselIndex("carousel", 1))
    }

    @Test
    fun `single item has no paging controls and inactive multiple items cannot move`() = runAndroidComposeUiTest {
        val model = mutableStateOf(carousel.copy(items = persistentListOf(item("A"))))
        setSafeContent { ElementPreview { InteractiveUiView(viewState(model.value), {}, {}) } }
        onNodeWithText("Item 1 of 1").assertIsDisplayed()
        onNodeWithText("Previous").assertDoesNotExist()
        onNodeWithText("Next").assertDoesNotExist()
        runOnIdle { model.value = carousel.copy(enabled = false) }
        onNodeWithText("Next").assertIsNotEnabled()
        onNodeWithText("Choose A").assertIsNotEnabled()
    }
}
