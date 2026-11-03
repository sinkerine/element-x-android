/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.libraries.mediaviewer.impl.viewer

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.designsystem.utils.AppDrawerGestureBlocker
import io.element.android.libraries.designsystem.utils.LocalAppDrawerGestureBlocker
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.setSafeContent
import org.junit.Test

class MediaViewerDrawerGesturesTest : RobolectricTest() {
    @Test
    fun `viewer blocks drawer gestures until disposed and blocks again when reopened`() = runAndroidComposeUiTest {
        val blocker = AppDrawerGestureBlocker()
        val visible = mutableStateOf(false)
        val state = mutableStateOf(aMediaViewerState())
        setSafeContent {
            CompositionLocalProvider(LocalAppDrawerGestureBlocker provides blocker) {
                if (visible.value) {
                    MediaViewerView(
                        state = state.value,
                        textFileViewer = { _, _ -> },
                        onBackClick = {},
                        audioFocus = null,
                    )
                }
            }
        }
        runOnIdle {
            assertThat(blocker.isBlocked).isFalse()
            visible.value = true
        }
        runOnIdle {
            assertThat(blocker.isBlocked).isTrue()
            state.value = aMediaViewerState(canShowInfo = false)
        }
        runOnIdle {
            assertThat(blocker.isBlocked).isTrue()
            visible.value = false
        }
        runOnIdle {
            assertThat(blocker.isBlocked).isFalse()
            visible.value = true
        }
        runOnIdle {
            assertThat(blocker.isBlocked).isTrue()
            visible.value = false
        }
        runOnIdle { assertThat(blocker.isBlocked).isFalse() }
    }

    @Test
    fun `viewer releases the old blocker when the drawer provider changes`() = runAndroidComposeUiTest {
        val first = AppDrawerGestureBlocker()
        val second = AppDrawerGestureBlocker()
        val provider = mutableStateOf(first)
        val visible = mutableStateOf(true)
        val state = aMediaViewerState()
        setSafeContent {
            CompositionLocalProvider(LocalAppDrawerGestureBlocker provides provider.value) {
                if (visible.value) {
                    MediaViewerView(
                        state = state,
                        textFileViewer = { _, _ -> },
                        onBackClick = {},
                        audioFocus = null,
                    )
                }
            }
        }
        runOnIdle {
            assertThat(first.isBlocked).isTrue()
            assertThat(second.isBlocked).isFalse()
            provider.value = second
        }
        runOnIdle {
            assertThat(first.isBlocked).isFalse()
            assertThat(second.isBlocked).isTrue()
            visible.value = false
        }
        runOnIdle {
            assertThat(first.isBlocked).isFalse()
            assertThat(second.isBlocked).isFalse()
        }
    }
}
