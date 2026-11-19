/*
 * Copyright (c) 2026 Element Creations Ltd.
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.libraries.textcomposer

import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.testtags.TestTags
import io.element.android.libraries.textcomposer.model.MessageComposerMode
import io.element.android.libraries.textcomposer.model.VoiceMessageState
import io.element.android.libraries.textcomposer.model.aTextEditorStateMarkdown
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.wysiwyg.display.TextDisplay
import org.junit.Test

class TextComposerEndButtonTest : RobolectricTest() {
    @Test
    fun `editing a message with text shows the send edited message button`() = runAndroidComposeUiTest {
        setTextComposer(text = "Some text", composerMode = aMessageComposerModeEdit())
        onNodeWithContentDescription(getString(CommonStrings.action_send_edited_message)).assertIsDisplayed()
    }

    @Test
    fun `emptying the composer while editing keeps the send edited message button`() = runAndroidComposeUiTest {
        setTextComposer(text = "", composerMode = aMessageComposerModeEdit())
        onNodeWithContentDescription(getString(CommonStrings.action_send_edited_message)).assertIsDisplayed()
        onNodeWithContentDescription(getString(CommonStrings.a11y_voice_message_record)).assertDoesNotExist()
    }

    @Test
    fun `emptying the composer while editing a caption keeps the send edited message button`() = runAndroidComposeUiTest {
        setTextComposer(text = "", composerMode = aMessageComposerModeEditCaption(content = "An existing caption"))
        onNodeWithContentDescription(getString(CommonStrings.action_send_edited_message)).assertIsDisplayed()
        onNodeWithContentDescription(getString(CommonStrings.a11y_voice_message_record)).assertDoesNotExist()
    }

    @Test
    fun `an empty composer outside of edition still offers to record a voice message`() = runAndroidComposeUiTest {
        setTextComposer(text = "", composerMode = MessageComposerMode.Normal)
        onNodeWithContentDescription(getString(CommonStrings.a11y_voice_message_record)).assertIsDisplayed()
    }

    @Test
    fun `editing a caption keeps its native input visible in an intrinsically sized composer`() = runAndroidComposeUiTest {
        setTextComposer(
            text = "Q3 docs",
            composerMode = aMessageComposerModeEditCaption(content = "Q3 docs"),
            modifier = Modifier.width(360.dp).height(IntrinsicSize.Min),
        )
        runOnIdle {
            val editor = activity!!.window.decorView.findViewWithTag<EditText>(TestTags.plainTextEditor.value)
            assertThat(editor.text.toString()).isEqualTo("Q3 docs")
            assertThat(editor.lineHeight).isGreaterThan(0)
            assertThat(editor.height).isAtLeast(editor.lineHeight)
        }
    }

    @Test
    fun `an intrinsically sized caption editor shows every line`() = runAndroidComposeUiTest {
        val caption = "First line\nSecond line\nThird line"
        setTextComposer(
            text = caption,
            composerMode = aMessageComposerModeEditCaption(content = caption),
            modifier = Modifier.width(360.dp).height(IntrinsicSize.Min),
        )
        runOnIdle {
            val editor = activity!!.window.decorView.findViewWithTag<EditText>(TestTags.plainTextEditor.value)
            assertThat(editor.layout.lineCount).isEqualTo(3)
            assertThat(editor.height).isAtLeast(editor.layout.height + editor.compoundPaddingTop + editor.compoundPaddingBottom)
        }
    }

    @Test
    fun `an intrinsically sized normal composer shows every line`() = runAndroidComposeUiTest {
        setTextComposer(
            text = "First line\nSecond line\nThird line",
            composerMode = MessageComposerMode.Normal,
            modifier = Modifier.width(360.dp).height(IntrinsicSize.Min),
        )
        runOnIdle {
            val editor = activity!!.window.decorView.findViewWithTag<EditText>(TestTags.plainTextEditor.value)
            assertThat(editor.layout.lineCount).isEqualTo(3)
            assertThat(editor.height).isAtLeast(editor.layout.height + editor.compoundPaddingTop + editor.compoundPaddingBottom)
        }
    }

    private fun AndroidComposeUiTest<ComponentActivity>.getString(resId: Int): String {
        return activity!!.getString(resId)
    }

    private fun AndroidComposeUiTest<ComponentActivity>.setTextComposer(
        text: String,
        composerMode: MessageComposerMode,
        modifier: Modifier = Modifier,
    ) {
        setContent {
            TextComposer(
                modifier = modifier,
                state = aTextEditorStateMarkdown(initialText = text, initialFocus = true),
                voiceMessageState = VoiceMessageState.Idle,
                composerMode = composerMode,
                onRequestFocus = {},
                onSendMessage = {},
                onResetComposerMode = {},
                onAddAttachment = {},
                onDismissTextFormatting = {},
                onVoiceRecorderEvent = {},
                onVoicePlayerEvent = {},
                onSendVoiceMessage = {},
                onDeleteVoiceMessage = {},
                onError = {},
                onTyping = {},
                onReceiveSuggestion = {},
                onSelectRichContent = null,
                resolveMentionDisplay = { _, _ -> TextDisplay.Plain },
                resolveAtRoomMentionDisplay = { TextDisplay.Plain },
            )
        }
    }
}
