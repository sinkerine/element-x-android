/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package io.element.android.features.messages.impl.threads.mymessages

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.setSafeContent
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadMyMessagesPreviewCardTest : RobolectricTest() {
    @Test
    fun `card shows full body clamped to eight lines edited root and predecessor`() = runAndroidComposeUiTest {
        val state = aThreadMyMessagesState()
        val body = (1..12).joinToString("\n") { "Body line $it" }
        setSafeContent {
            ThreadMyMessagesPreviewCard(
                state.items.first().copy(body = body, isRoot = true, isEdited = true, context = ThreadMyMessageContext("Sam", "Before this")),
                state.ownAvatar,
            )
        }
        onNodeWithText("You · Today at 10:42").assertIsDisplayed()
        onNodeWithText("(edited)").assertIsDisplayed()
        onNodeWithText("Thread root").assertIsDisplayed()
        onNodeWithText("After Sam: Before this").assertIsDisplayed()
        val results = mutableListOf<TextLayoutResult>()
        onNodeWithText(body).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(8, results.single().lineCount)
    }

    @Test
    fun `plain card omits optional badges and unavailable context`() = runAndroidComposeUiTest {
        val state = aThreadMyMessagesState()
        setSafeContent { ThreadMyMessagesPreviewCard(state.items.last().copy(isRoot = false), state.ownAvatar) }
        onNodeWithText("Plans for this week").assertIsDisplayed()
        onNodeWithText("(edited)").assertDoesNotExist()
        onNodeWithText("Thread root").assertDoesNotExist()
        onNodeWithText("After", substring = true).assertDoesNotExist()
    }
}
