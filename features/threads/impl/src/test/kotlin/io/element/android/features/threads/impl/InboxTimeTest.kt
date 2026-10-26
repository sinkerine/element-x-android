/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package io.element.android.features.threads.impl

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Test
import java.text.DateFormat
import java.util.Date

class InboxTimeTest : RobolectricTest() {
    @Test
    fun `production localized label clamps future and selects boundaries through seven days`() =
        runAndroidComposeUiTest<androidx.activity.ComponentActivity> {
            val now = 1_800_000_000_000L
            val ages = listOf(
                -60_000L,
                0L,
                59_999L,
                60_000L,
                240_000L,
                3_599_999L,
                3_600_000L,
                10_800_000L,
                86_399_999L,
                86_400_000L,
                172_800_000L,
                604_799_999L,
                604_800_000L,
                691_200_000L
            )
            setContent {
                ElementPreview {
                    androidx.compose.foundation.layout.Column {
                        ages.forEachIndexed { index, age -> Text("$index: " + inboxTimeLabel(now - age, now)) }
                    }
                }
            }
            listOf("now", "now", "now", "1m", "4m", "59m", "1h", "3h", "23h", "1d", "2d", "6d").forEachIndexed { index, label ->
                onNodeWithText("$index: $label").assertExists()
            }
            onNodeWithText("12: " + DateFormat.getDateInstance(DateFormat.SHORT).format(Date(now - 604_800_000L))).assertExists()
            onNodeWithText("13: " + DateFormat.getDateInstance(DateFormat.SHORT).format(Date(now - 691_200_000L))).assertExists()
        }
}
