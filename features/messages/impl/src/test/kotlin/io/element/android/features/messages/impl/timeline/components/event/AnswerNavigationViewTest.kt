/*
 * Copyright 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.messages.impl.timeline.components.event

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.setSafeContent
import io.element.android.wysiwyg.link.Link
import org.junit.Test

class AnswerNavigationViewTest : RobolectricTest() {
    @Test
    fun `duplicate labels select the numbered section and current marker is visible`() = runAndroidComposeUiTest {
        var selected: Int? = null
        val navigation = AnswerNavigationPreviewProvider().values.first()
        setSafeContent {
            ElementPreview { AnswerIndexContent(navigation, 2, { selected = it }, {}) }
        }
        onNodeWithText("Current").assertIsDisplayed()
        onNodeWithText("3.").performClick()
        assertThat(selected).isEqualTo(2)
        onNodeWithText("2.").performClick()
        assertThat(selected).isEqualTo(1)
    }

    @Test
    fun `source shows author label and host and forwards the original link`() = runAndroidComposeUiTest {
        var opened: Link? = null
        val navigation = AnswerNavigationPreviewProvider().values.first()
        setSafeContent {
            ElementPreview { AnswerIndexContent(navigation, null, {}, { opened = it }) }
        }
        onNodeWithText("spec.matrix.org").assertIsDisplayed()
        onNodeWithText("Matrix client–server specification").performClick()
        assertThat(opened?.url).isEqualTo("https://spec.matrix.org")
        assertThat(opened?.text).isEqualTo("Matrix client–server specification")
    }

    @Test
    fun `source free answers omit sources area`() = runAndroidComposeUiTest {
        val navigation = AnswerNavigationPreviewProvider().values.last()
        setSafeContent {
            ElementPreview { AnswerIndexContent(navigation, null, {}, {}) }
        }
        onNodeWithText("Sources").assertDoesNotExist()
        onNodeWithText("Current").assertDoesNotExist()
    }

    @Test
    fun `heading action reopens index with the selected section marked`() = runAndroidComposeUiTest {
        val navigation = AnswerNavigationPreviewProvider().values.first()
        setSafeContent {
            ElementPreview { AnswerNavigationView(navigation, {}, {}) }
        }
        onNodeWithText("Current").assertDoesNotExist()
        onAllNodesWithText("Sections")[2].performClick()
        onNodeWithText("Current").assertIsDisplayed()
        onNodeWithText("3.").assertIsDisplayed()
    }
}
