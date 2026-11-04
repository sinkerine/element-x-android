/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.messages.impl.timeline.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.messages.impl.interactiveui.interactiveUiContent
import io.element.android.features.messages.impl.timeline.aTimelineItemEvent
import io.element.android.features.messages.impl.timeline.aTimelineRoomInfo
import io.element.android.features.messages.impl.timeline.di.LocalTimelineItemPresenterFactories
import io.element.android.features.messages.impl.timeline.di.TimelineItemPresenterFactories
import io.element.android.features.messages.impl.timeline.di.TimelineItemPresenterFactory
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemInteractiveUiContent
import io.element.android.features.messages.impl.timeline.protection.aTimelineProtectionState
import io.element.android.features.threads.impl.DefaultThreadsDrawer
import io.element.android.features.threads.test.FakeThreadsTriageQueue
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.featureflag.test.FakeFeatureFlagService
import io.element.android.libraries.interactiveui.core.session.InteractiveUiPhase
import io.element.android.libraries.interactiveui.ui.graph.InteractiveUiGraphInput
import io.element.android.libraries.interactiveui.ui.graph.layoutLayeredGraph
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiBadgeTone
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiViewEvent
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiViewState
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.setSafeContent
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Test
import org.robolectric.annotation.Config
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiRenderNode as Render

@Config(qualifiers = "w360dp-h1000dp")
class TimelineItemEventRowInteractiveUiGesturesTest : RobolectricTest() {
    @Test
    fun `carousel drag changes exactly one page without reply or drawer and header still replies`() = runAndroidComposeUiTest {
        val fixture = Fixture()
        setTimeline(fixture, carousel())
        onNodeWithTag("interactive-ui-carousel-carousel", useUnmergedTree = true).performTouchInput { swipeLeft() }
        waitForIdle()
        onNodeWithText("Item 2 of 3").assertIsDisplayed()
        runOnIdle {
            assertThat(
                fixture.events.filterIsInstance<InteractiveUiViewEvent.SetCarouselIndex>()
            ).containsExactly(InteractiveUiViewEvent.SetCarouselIndex("carousel", 1))
            assertThat(fixture.replies).isEqualTo(0)
            assertThat(fixture.drawer.isOpen.value).isFalse()
        }
        onNodeWithText("Card header", useUnmergedTree = true).performTouchInput { slowSwipeRight() }
        waitForIdle()
        runOnIdle {
            assertThat(fixture.replies).isEqualTo(1)
            assertThat(fixture.drawer.isOpen.value).isFalse()
        }
    }

    @Test
    fun `graph consumes drag without reply or drawer and full width bubble fits 360 dp`() = runAndroidComposeUiTest {
        val graph = Render.Graph(
            "graph",
            layoutLayeredGraph(InteractiveUiGraphInput(
                false,
                listOf("One", "Two", "Three"),
            (0..2).map { InteractiveUiGraphInput.Node("n$it", "Node $it", null, it, InteractiveUiBadgeTone.Neutral) },
            listOf(InteractiveUiGraphInput.Edge("n0", "n1", null, false), InteractiveUiGraphInput.Edge("n1", "n2", null, false))
            )),
            null,
            false,
            true
        )
        val fixture = Fixture()
        setTimeline(fixture, graph)
        val before = onNodeWithText("Node 0", useUnmergedTree = true).getUnclippedBoundsInRoot()
        onNodeWithTag("interactive-ui-graph-diagram", useUnmergedTree = true).performTouchInput { swipeLeft(startX = 220f, endX = 20f) }
        waitForIdle()
        val after = onNodeWithText("Node 0", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertThat(after.left.value).isLessThan(before.left.value)
        runOnIdle {
            assertThat(fixture.replies).isEqualTo(0)
            assertThat(fixture.drawer.isOpen.value).isFalse()
        }
        val bubble = onNodeWithTag("message_bubble", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertThat(bubble.left.value).isAtLeast(0f)
        assertThat(bubble.right.value).isAtMost(360f)
        assertThat((bubble.right - bubble.left).value).isGreaterThan(250f)
    }

    @Test
    @Config(qualifiers = "w1000dp-h1000dp")
    fun `wide row bubble is capped at 680 dp`() = runAndroidComposeUiTest {
        setTimeline(Fixture(), carousel(), widthDp = 1000)
        val bubble = onNodeWithTag("message_bubble", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertThat((bubble.right - bubble.left).value).isWithin(1f).of(680f)
    }

    private class Fixture {
        lateinit var drawer: DefaultThreadsDrawer
        var replies = 0
        val events = mutableListOf<InteractiveUiViewEvent>()
    }

    private fun carousel() = Render.Carousel("carousel", "Products", 0, (0..2).map {
        Render.Carousel.Item("item$it", null, "Photo $it", "Product $it", null, persistentListOf(), null, null, false, false)
    }.toImmutableList(), true)

    private fun TouchInjectionScope.slowSwipeRight() {
        down(center.copy(x = 4f))
        for (x in 8..260 step 4) moveTo(center.copy(x = x.toFloat()), delayMillis = 8)
        up()
    }

    private fun AndroidComposeUiTest<ComponentActivity>.setTimeline(fixture: Fixture, root: Render, widthDp: Int = 360) {
        val state = mutableStateOf(InteractiveUiViewState("Card header", InteractiveUiPhase.Active, null, "Sender", false, false, null, root, {}))
        state.value = state.value.copy(eventSink = { event ->
            fixture.events += event
            if (event is InteractiveUiViewEvent.SetCarouselIndex) {
                state.value =
                state.value.copy(root = (state.value.root as Render.Carousel).copy(index = event.index))
            }
        })
        val factories = TimelineItemPresenterFactories(mapOf(
            TimelineItemInteractiveUiContent::class to
            TimelineItemPresenterFactory<TimelineItemInteractiveUiContent, InteractiveUiViewState> { Presenter { state.value } }
        ))
        setSafeContent {
            val scope = rememberCoroutineScope()
            val drawer = remember {
                DefaultThreadsDrawer(FakeThreadsTriageQueue(), FakeFeatureFlagService(), FakeMatrixClient(sessionCoroutineScope = scope))
                .also { fixture.drawer = it }
            }
            ElementTheme {
                CompositionLocalProvider(LocalTimelineItemPresenterFactories provides factories) {
                    drawer.Layout(true, { Text("Inbox", it) }, Modifier.width(widthDp.dp).height(1000.dp)) {
                        TimelineItemEventRow(
                            event = aTimelineItemEvent(content = interactiveUiContent(), canBeRepliedTo = true),
                            timelineMode = Timeline.Mode.Live,
                            timelineRoomInfo = aTimelineRoomInfo(isDm = true),
                            timelineProtectionState = aTimelineProtectionState(),
                            isLastOutgoingMessage = false,
                            displayThreadSummaries = false,
                            onEventClick = {},
                            onGalleryItemClick = {},
                            onLongClick = {},
                            onLinkClick = {},
                            onLinkLongClick = {},
                            onUserDataClick = {},
                            inReplyToClick = {},
                            onReactionClick = { _, _ -> },
                            onReactionLongClick = { _, _ -> },
                            onMoreReactionsClick = {},
                            onReadReceiptClick = {},
                            onSwipeToReply = { fixture.replies++ },
                            eventSink = {},
                        )
                    }
                }
            }
        }
        waitForIdle()
    }
}
