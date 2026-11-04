/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.libraries.interactiveui.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiNode
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiBadgeTone
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiRenderNode
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.setSafeContent
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InteractiveUiResponsiveTest : RobolectricTest() {
    @Test
    fun `payload buttons links and badges wrap fully at 360dp double font in RTL`() = runAndroidComposeUiTest {
        val label = "A long response label that must remain readable at large font sizes"
        val link = "A long source reference with a descriptive explanation"
        val badge = "A long informational badge that also needs to wrap completely"
        val root = InteractiveUiRenderNode.Stack("root", false, 8.dp, persistentListOf(
            button.copy(label = label),
            InteractiveUiRenderNode.Link("link", link, "example.org", true, true),
            InteractiveUiRenderNode.Badge("badge", badge, InteractiveUiBadgeTone.Warning),
            button.copy(id = "danger", label = "Delete item", variant = InteractiveUiNode.ButtonVariant.Danger),
        ))
        setSafeContent {
            ElementPreview {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f), LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) { InteractiveUiView(viewState(root), {}, {}) }
                }
            }
        }
        listOf(label, "$link · example.org (Source)", badge).forEach { text ->
            onNodeWithText(text).performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertThat(layouts).isNotEmpty()
            assertThat(layouts.first().hasVisualOverflow).isFalse()
            assertThat(layouts.first().lineCount).isGreaterThan(1)
        }
    }
}
