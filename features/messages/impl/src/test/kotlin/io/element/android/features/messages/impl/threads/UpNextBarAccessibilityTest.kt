/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package io.element.android.features.messages.impl.threads

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Test

class UpNextBarAccessibilityTest : RobolectricTest() {
    @Test
    fun `queue changes update complete polite announcement`() = runAndroidComposeUiTest<androidx.activity.ComponentActivity> {
        val preview = mutableStateOf<String?>("Room · Lena: First")
        setContent { ElementPreview { UpNextBar(preview.value, 2, {}) } }
        onNodeWithContentDescription("Up next, Room · Lena: First, Next (2)")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        runOnIdle { preview.value = "Room · Lena: Second" }
        onNodeWithContentDescription("Up next, Room · Lena: Second, Next (2)").assertExists()
        runOnIdle { preview.value = null }
        onNodeWithContentDescription("Up next, You’re all caught up, All read").assertExists()
    }
}
