/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.libraries.interactiveui.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.htmlrenderer.api.DocumentNode
import io.element.android.libraries.htmlrenderer.api.ParagraphNode
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiRenderNode
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiViewEvent
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.setSafeContent
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test

class InteractiveUiRichTextTest : RobolectricTest() {
    @Test
    fun `sanitised document is static and preferred over plain fallback`() = runAndroidComposeUiTest {
        val events = mutableListOf<InteractiveUiViewEvent>()
        val opened = mutableListOf<String>()
        val document = DocumentNode(persistentListOf(ParagraphNode(AnnotatedString("Static rich text"))))
        setSafeContent {
            ElementPreview {
                InteractiveUiView(viewState(InteractiveUiRenderNode.RichText("rich", "Plain fallback", document), events::add), opened::add, {})
            }
        }
        onNodeWithText("Static rich text").assertIsDisplayed().performClick()
        onNodeWithText("Plain fallback").assertDoesNotExist()
        assertThat(events).isEmpty()
        assertThat(opened).isEmpty()
    }
}
