/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.libraries.designsystem.utils

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import com.google.common.truth.Truth.assertThat
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.setSafeContent
import org.junit.Test

class AppDrawerGesturesTest : RobolectricTest() {
    @Test
    fun `requests are counted and unmatched unblocks cannot underflow`() {
        val blocker = AppDrawerGestureBlocker()
        blocker.unblock()
        assertThat(blocker.isBlocked).isFalse()
        blocker.block()
        blocker.block()
        blocker.unblock()
        assertThat(blocker.isBlocked).isTrue()
        blocker.unblock()
        assertThat(blocker.isBlocked).isFalse()
        blocker.unblock()
        blocker.block()
        assertThat(blocker.isBlocked).isTrue()
    }

    @Test
    fun `composed requests release independently and follow provider replacement`() = runAndroidComposeUiTest {
        val first = AppDrawerGestureBlocker()
        val second = AppDrawerGestureBlocker()
        val provider = mutableStateOf(first)
        val count = mutableIntStateOf(2)
        setSafeContent {
            CompositionLocalProvider(LocalAppDrawerGestureBlocker provides provider.value) {
                if (count.intValue > 0) BlockAppDrawerGestures()
                if (count.intValue > 1) BlockAppDrawerGestures()
            }
        }
        runOnIdle { assertThat(first.isBlocked).isTrue() }
        runOnIdle { count.intValue = 1 }
        runOnIdle { assertThat(first.isBlocked).isTrue() }
        runOnIdle { provider.value = second }
        runOnIdle {
            assertThat(first.isBlocked).isFalse()
            assertThat(second.isBlocked).isTrue()
            count.intValue = 0
        }
        runOnIdle { assertThat(second.isBlocked).isFalse() }
    }

    @Test
    fun `no provider is a no-op`() = runAndroidComposeUiTest {
        val visible = mutableStateOf(true)
        setSafeContent { if (visible.value) BlockAppDrawerGestures() }
        runOnIdle { visible.value = false }
        waitForIdle()
    }
}
