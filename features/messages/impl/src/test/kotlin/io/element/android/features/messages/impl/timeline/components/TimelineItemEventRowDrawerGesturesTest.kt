/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.messages.impl.timeline.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.messages.impl.timeline.aTimelineItemEvent
import io.element.android.features.messages.impl.timeline.aTimelineRoomInfo
import io.element.android.features.messages.impl.timeline.protection.aTimelineProtectionState
import io.element.android.features.threads.impl.DefaultThreadsDrawer
import io.element.android.features.threads.test.FakeThreadsTriageQueue
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.featureflag.test.FakeFeatureFlagService
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.setSafeContent
import org.junit.Test
import org.robolectric.annotation.Config

@Config(qualifiers = "w450dp-h800dp")
class TimelineItemEventRowDrawerGesturesTest : RobolectricTest() {
    @Test
    fun `slow swipe on replyable timeline row replies before drawer can open`() = runAndroidComposeUiTest {
        val fixture = Fixture()
        setTimelineDrawer(fixture)
        onNodeWithTag("message", useUnmergedTree = true).performTouchInput { slowSwipeRight() }
        runOnIdle {
            assertThat(fixture.replies).isEqualTo(1)
            assertThat(fixture.drawer.isOpen.value).isFalse()
            assertThat(fixture.queue.startVisitCount).isEqualTo(0)
        }
    }

    @Test
    fun `non-message swipe opens drawer and message long press still works`() = runAndroidComposeUiTest {
        val fixture = Fixture()
        setTimelineDrawer(fixture)
        onNodeWithTag("message", useUnmergedTree = true).performTouchInput { longClick() }
        runOnIdle { assertThat(fixture.longClicks).isEqualTo(1) }
        onNodeWithTag("non-message").performTouchInput { slowSwipeRight() }
        runOnIdle {
            assertThat(fixture.drawer.isOpen.value).isTrue()
            assertThat(fixture.queue.startVisitCount).isEqualTo(1)
            assertThat(fixture.replies).isEqualTo(0)
        }
    }

    // Small motion increments cross the drawer's original slop before the timeline's 3x slop.
    // A single large move could cross both thresholds together and hide the priority regression.
    private fun TouchInjectionScope.slowSwipeRight() {
        down(center.copy(x = 30f))
        for (x in 34..350 step 4) moveTo(center.copy(x = x.toFloat()), delayMillis = 8)
        up()
    }

    private class Fixture {
        val queue = FakeThreadsTriageQueue()
        lateinit var drawer: DefaultThreadsDrawer
        var replies = 0
        var longClicks = 0
    }

    private fun AndroidComposeUiTest<ComponentActivity>.setTimelineDrawer(fixture: Fixture) {
        setSafeContent {
            val scope = rememberCoroutineScope()
            val drawer = remember {
                DefaultThreadsDrawer(fixture.queue, FakeFeatureFlagService(), FakeMatrixClient(sessionCoroutineScope = scope))
                    .also { fixture.drawer = it }
            }
            ElementTheme {
                drawer.Layout(
                    isAvailable = true,
                    inbox = { Text("Inbox", it) },
                    modifier = Modifier.width(420.dp).height(640.dp),
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().height(100.dp).testTag("non-message"))
                        TimelineItemEventRow(
                            event = aTimelineItemEvent(canBeRepliedTo = true),
                            timelineMode = Timeline.Mode.Live,
                            timelineRoomInfo = aTimelineRoomInfo(),
                            timelineProtectionState = aTimelineProtectionState(),
                            isLastOutgoingMessage = false,
                            displayThreadSummaries = false,
                            onEventClick = {},
                            onGalleryItemClick = {},
                            onLongClick = { fixture.longClicks++ },
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
                            eventContentView = { modifier, _ ->
                                Text("Synthetic message", modifier.height(80.dp).testTag("message"))
                            },
                        )
                    }
                }
            }
        }
        waitForIdle()
        runOnIdle { assertThat(fixture.drawer.isEnabled.value).isTrue() }
    }
}
