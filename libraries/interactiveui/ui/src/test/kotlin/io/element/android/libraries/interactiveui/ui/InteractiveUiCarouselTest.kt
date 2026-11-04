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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import io.element.android.libraries.interactiveui.core.action.InteractiveUiEventContext
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiConstants
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiParseResult
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiParser
import io.element.android.libraries.interactiveui.core.session.InteractiveUiSession
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiRenderNode
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiViewEvent
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.setSafeContent
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import java.io.File

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
                }, {}, fallback = {})
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
                    }.copy(title = title.value), {}, fallback = {})
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
    fun `session and pager recreation at new width retains selection and next swipe advances exactly once`() = runAndroidComposeUiTest {
        val payload = (InteractiveUiParser.parse(buildJsonObject {
            put(InteractiveUiConstants.PAYLOAD_KEY, Json.parseToJsonElement(
                File("../core/src/test/resources/interactive-ui/fixtures/valid/product-carousel.json").readText()
            ))
        }) as InteractiveUiParseResult.Valid).payload
        val scope = CoroutineScope(Dispatchers.Main)
        fun createSession(saved: JsonElement? = null) = InteractiveUiSession(
            InteractiveUiEventContext("$" + "ui", "@sender:test", null),
            payload,
            saved,
            scope,
            { Result.success(Unit) },
            {}
        )
        var session = createSession()
        val generation = mutableIntStateOf(0)
        val width = mutableStateOf(600.dp)
        val state = mutableStateOf(session.state.value)
        val moves = mutableListOf<Int>()
        try {
            setSafeContent {
                ElementPreview {
                    key(generation.intValue) {
                        Box(Modifier.width(width.value)) {
                            InteractiveUiView(InteractiveUiRenderTreeBuilder.build(
                                state.value,
                                persistentMapOf(),
                                persistentMapOf(),
                                false,
                                "Sender"
                            ) { event ->
                                if (event is InteractiveUiViewEvent.SetCarouselIndex) {
                                    moves += event.index
                                    session.setCarouselIndex(event.nodeId, event.index)
                                    state.value = session.state.value
                                }
                            }, {}, fallback = {})
                        }
                    }
                }
            }
            repeat(2) {
                onNodeWithText("Next").performClick()
                waitForIdle()
            }
            onNodeWithText("Item 3 of 5").assertIsDisplayed()
            onNodeWithTag("interactive-ui-carousel-carousel").performTouchInput { swipeLeft() }
            waitForIdle()
            onNodeWithText("Item 4 of 5").assertIsDisplayed()
            runOnIdle {
                val saved = session.savedStateEnvelope()
                session.dispose()
                session = createSession(saved)
                state.value = session.state.value
                width.value = 360.dp
                generation.intValue++
            }
            waitForIdle()
            onNodeWithText("Item 4 of 5").assertIsDisplayed()
            assertThat(moves).containsExactly(1, 2, 3).inOrder()
            onNodeWithTag("interactive-ui-carousel-carousel").performTouchInput { swipeLeft() }
            waitForIdle()
            onNodeWithText("Item 5 of 5").assertIsDisplayed()
            assertThat(moves).containsExactly(1, 2, 3, 4).inOrder()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `single item has no paging controls and inactive multiple items cannot move`() = runAndroidComposeUiTest {
        val model = mutableStateOf(carousel.copy(items = persistentListOf(item("A"))))
        setSafeContent { ElementPreview { InteractiveUiView(viewState(model.value), {}, fallback = {}) } }
        onNodeWithText("Item 1 of 1").assertIsDisplayed()
        onNodeWithText("Previous").assertDoesNotExist()
        onNodeWithText("Next").assertDoesNotExist()
        runOnIdle { model.value = carousel.copy(enabled = false) }
        onNodeWithText("Next").assertIsNotEnabled()
        onNodeWithText("Choose A").assertIsNotEnabled()
    }
}
