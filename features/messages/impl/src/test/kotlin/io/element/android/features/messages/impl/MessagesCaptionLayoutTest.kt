/*
 * Copyright (c) 2026 Element Creations Ltd.
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.messages.impl

import android.graphics.Rect
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.crypto.identity.aRoomMemberIdentityStateChange
import io.element.android.features.messages.impl.crypto.identity.anIdentityChangeState
import io.element.android.features.messages.impl.messagecomposer.aMessageComposerState
import io.element.android.libraries.testtags.TestTags
import io.element.android.libraries.textcomposer.aMessageComposerModeEditCaption
import io.element.android.libraries.textcomposer.model.aTextEditorStateMarkdown
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Test

class MessagesCaptionLayoutTest : RobolectricTest() {
    @Test
    fun `caption input remains visible in a short viewport without an identity banner`() = runAndroidComposeUiTest {
        setMessagesView(
            state = captionState(showBanner = false),
            inspectionMode = false,
            modifierProvider = { Modifier.width(360.dp).height(360.dp) },
        )
        assertCaptionInputVisible()
    }

    @Test
    fun `caption input remains visible beside an identity banner in a short viewport`() = runAndroidComposeUiTest {
        setMessagesView(
            state = captionState(showBanner = true),
            inspectionMode = false,
            modifierProvider = { Modifier.width(360.dp).height(360.dp) },
        )
        assertCaptionInputVisible()
    }

    @Test
    fun `caption input survives viewport shrink and identity banner removal`() = runAndroidComposeUiTest {
        val height = mutableStateOf(640.dp)
        val screen = mutableStateOf(captionState(showBanner = true))
        setMessagesView(
            state = screen.value,
            stateProvider = { screen.value },
            inspectionMode = false,
            modifierProvider = { Modifier.width(360.dp).height(height.value) },
        )
        assertCaptionInputVisible()
        runOnIdle { height.value = 360.dp }
        assertCaptionInputVisible()
        runOnIdle { screen.value = screen.value.copy(identityChangeState = anIdentityChangeState()) }
        assertCaptionInputVisible()
    }

    private fun captionState(showBanner: Boolean) = aMessagesState(
        composerState = aMessageComposerState(
            textEditorState = aTextEditorStateMarkdown(initialText = "Q3 docs", initialFocus = true),
            mode = aMessageComposerModeEditCaption(content = "Q3 docs"),
        ),
        identityChangeState = anIdentityChangeState(
            if (showBanner) listOf(aRoomMemberIdentityStateChange()) else emptyList(),
        ),
    )

    private fun AndroidComposeUiTest<ComponentActivity>.assertCaptionInputVisible() {
        waitForIdle()
        runOnIdle {
            val editor = activity!!.window.decorView.findViewWithTag<EditText>(TestTags.plainTextEditor.value)
            assertThat(editor.text.toString()).isEqualTo("Q3 docs")
            assertThat(editor.lineHeight).isGreaterThan(0)
            assertThat(editor.height).isAtLeast(editor.lineHeight)
            val visibleBounds = Rect()
            assertThat(editor.getGlobalVisibleRect(visibleBounds)).isTrue()
            assertThat(visibleBounds.height()).isAtLeast(editor.lineHeight)
        }
    }
}
