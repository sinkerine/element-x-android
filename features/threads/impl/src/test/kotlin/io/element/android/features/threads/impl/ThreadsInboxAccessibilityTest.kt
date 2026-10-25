/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package io.element.android.features.threads.impl

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
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
                    rootPreview = "Where should we meet?", replyPreview = "Lena: Here", visited = false,
                    onOpen = {}, onLongClick = {}, onMarkRead = { marked = true }, onTouch = {},
                )
            }
        }
        onNodeWithContentDescription("Android team, thread:", substring = true)
            .assertHasClickAction()
            .performCustomAccessibilityActionWithLabel("Mark as read")
        runOnIdle { assertThat(marked).isTrue() }
    }

    @Test
    fun `tabs announce unread counts and remain actionable`() = runAndroidComposeUiTest<androidx.activity.ComponentActivity> {
        val state = ThreadsInboxStatePreviewParam().values.first()
        setContent { ElementPreview { ThreadsInboxView(state, {}, {}) } }
        onNodeWithContentDescription("My threads, 1 unread").assertHasClickAction()
        onNodeWithContentDescription("Other threads, 0 unread").assertHasClickAction()
    }
}
