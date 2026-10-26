/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package io.element.android.features.threads.impl

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Test

class ThreadsInboxAccessibilityTest : RobolectricTest() {
    @Test
    fun `row merges its description and exposes accessible mark read action`() = runAndroidComposeUiTest<androidx.activity.ComponentActivity> {
        var marked = false
        setContent {
            ElementPreview {
                ThreadsInboxRow(
                    thread = previewThread().copy(numUnreadMentions = 1),
                    rootPreview = "Where should we meet?",
                    replyPreview = "Lena: Here",
                    visited = false,
                    onOpen = {},
                    onLongClick = {},
                    onMarkRead = { marked = true },
                    onTouch = {},
                )
            }
        }
        onNodeWithContentDescription("Android team, thread:", substring = true)
            .assertHasClickAction()
            .performCustomAccessibilityActionWithLabel("Mark as read")
        runOnIdle { assertThat(marked).isTrue() }
    }

    @Test
    fun `visited row with new unread mention displays mention badge`() = runAndroidComposeUiTest<androidx.activity.ComponentActivity> {
        setContent {
            ElementPreview {
            ThreadsInboxRow(
                thread = previewThread().copy(numUnreadMentions = 1),
                rootPreview = "Root",
                replyPreview = "New reply",
                visited = true,
                onOpen = {},
                onLongClick = {},
                onMarkRead = {},
                onTouch = {},
            )
        }
        }
        onNodeWithText("@ 1").assertExists()
    }

    @Test
    fun `loading shows three shimmer placeholders`() = runAndroidComposeUiTest<androidx.activity.ComponentActivity> {
        mainClock.autoAdvance = false
        setContent { ElementPreview { ThreadsInboxLoading() } }
        onNodeWithTag("inbox-placeholder-0").assertExists()
        onNodeWithTag("inbox-placeholder-1").assertExists()
        onNodeWithTag("inbox-placeholder-2").assertExists()
    }

    @Test
    fun `single list has no tabs, filters or latest unread banner`() = runAndroidComposeUiTest<androidx.activity.ComponentActivity> {
        val state = ThreadsInboxStatePreviewParam().values.first()
        setContent { ElementPreview { ThreadsInboxView(state, {}, {}) } }
        onNodeWithContentDescription("Android team, thread:", substring = true).assertExists()
        onNodeWithText("My threads").assertDoesNotExist()
        onNodeWithText("Other threads").assertDoesNotExist()
        onNodeWithText("Unread").assertDoesNotExist()
        onNodeWithText("All").assertDoesNotExist()
        onNodeWithText("Open latest unread").assertDoesNotExist()
    }
}
