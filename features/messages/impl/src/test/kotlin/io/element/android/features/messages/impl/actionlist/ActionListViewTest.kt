/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.messages.impl.actionlist

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.actionlist.model.TimelineItemAction
import io.element.android.features.messages.impl.crypto.sendfailure.VerifiedUserSendFailure
import io.element.android.features.messages.impl.timeline.aTimelineItemEvent
import io.element.android.features.messages.impl.timeline.components.event.aGalleryItem
import io.element.android.features.messages.impl.timeline.components.event.aTimelineItemGalleryContent
import io.element.android.features.messages.impl.timeline.model.event.GalleryItem
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.setSafeContent
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import org.robolectric.annotation.Config

class ActionListViewTest : RobolectricTest() {
    @Test
    @Config(qualifiers = "w393dp-h698dp")
    fun `touching edit caption in the real sheet clears it and delivers the gallery action once`() = runAndroidComposeUiTest {
        val event = aTimelineItemEvent(content = aTimelineItemGalleryContent(caption = "Q3 docs", items = listOf(
            aGalleryItem(filename = "red.png"),
            aGalleryItem(filename = "report.pdf", type = GalleryItem.Type.File),
            aGalleryItem(filename = "tone.wav", type = GalleryItem.Type.Audio),
            aGalleryItem(filename = "notes.txt", type = GalleryItem.Type.File),
            aGalleryItem(filename = "noext", type = GalleryItem.Type.File),
        )))
        val actions = persistentListOf(
            TimelineItemAction.Reply,
            TimelineItemAction.ReplyInThread,
            TimelineItemAction.Forward,
            TimelineItemAction.EditCaption,
            TimelineItemAction.CopyLink,
            TimelineItemAction.CopyCaption,
            TimelineItemAction.RemoveCaption,
            TimelineItemAction.ViewSource,
            TimelineItemAction.Redact,
        )
        val target = mutableStateOf<ActionListState.Target>(ActionListState.Target.Success(
            event = event,
            sentTimeFull = "Today",
            displayEmojiReactions = true,
            recentEmojis = persistentListOf("👍️", "👎️", "🔥", "❤️", "👏"),
            verifiedUserSendFailure = VerifiedUserSendFailure.None,
            actions = actions,
        ))
        val selected = mutableListOf<TimelineItemAction>()
        setSafeContent {
            ActionListView(
                state = ActionListState(target.value) { if (it == ActionListEvent.Clear) target.value = ActionListState.Target.None },
                onSelectAction = { action, selectedEvent ->
                    assertThat(selectedEvent).isEqualTo(event)
                    selected.add(action)
                },
                onEmojiReactionClick = { _, _ -> },
                onCustomReactionClick = {},
                onVerifiedUserSendFailureClick = {},
            )
        }
        mainClock.advanceTimeBy(1_000)
        onNode(isDialog()).performTouchInput { swipeUp() }
        mainClock.advanceTimeBy(1_000)
        onNodeWithText("Edit caption").performScrollTo().performTouchInput { click() }
        mainClock.advanceTimeBy(1_000)
        runOnIdle {
            assertThat(selected).containsExactly(TimelineItemAction.EditCaption)
            assertThat(target.value).isEqualTo(ActionListState.Target.None)
        }
    }
}
