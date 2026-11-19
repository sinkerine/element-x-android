/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalTestApi::class)

package io.element.android.features.messages.impl.actionlist

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.designsystem.theme.components.ModalBottomSheet
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.setSafeContent
import org.junit.Test
import org.robolectric.annotation.Config

class ActionListSheetInsetsTest : RobolectricTest() {
    @Test
    @Config(qualifiers = "w393dp-h698dp")
    fun `tall sheet with top inset settles after expansion and accepts a pointer click`() = runAndroidComposeUiTest {
        verifySheetSettles()
    }

    @Test
    @Config(qualifiers = "w393dp-h698dp")
    fun `content resize during expansion preserves pointer actions`() = runAndroidComposeUiTest {
        verifySheetSettles(heightAfterSwipe = 600.dp)
    }

    private fun AndroidComposeUiTest<ComponentActivity>.verifySheetSettles(heightAfterSwipe: Dp? = null) {
        lateinit var sheet: SheetState
        var clicks = 0
        val contentHeight = mutableStateOf(630.dp)
        val insets = WindowInsets(top = 24.dp)
        setSafeContent {
            val state = rememberBottomSheetState(initialValue = SheetValue.Hidden)
            SideEffect { sheet = state }
            ModalBottomSheet(
                onDismissRequest = {},
                scrollable = false,
                sheetState = state,
                contentWindowInsets = { insets },
            ) {
                Column(Modifier.fillMaxWidth().height(contentHeight.value)) {
                    Text("Action", modifier = Modifier.fillMaxWidth().height(56.dp).clickable { clicks++ })
                }
            }
        }
        mainClock.autoAdvance = false
        repeat(90) {
            mainClock.advanceTimeByFrame()
            waitForIdle()
        }
        onNode(isDialog()).performTouchInput { swipeUp() }
        if (heightAfterSwipe != null) {
            runOnIdle { contentHeight.value = heightAfterSwipe }
        }
        repeat(90) {
            mainClock.advanceTimeByFrame()
            waitForIdle()
        }
        runOnIdle {
            assertThat(sheet.currentValue).isEqualTo(SheetValue.Expanded)
            assertThat(sheet.isAnimationRunning).isFalse()
        }
        onNodeWithText("Action").performTouchInput { click() }
        runOnIdle { assertThat(clicks).isEqualTo(1) }
    }
}
