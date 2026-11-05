/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.libraries.htmlrenderer.api.ui

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.htmlrenderer.api.DocumentNode
import io.element.android.libraries.htmlrenderer.api.ListItemNode
import io.element.android.libraries.htmlrenderer.api.ListNode
import io.element.android.libraries.htmlrenderer.api.MathsInlineContent
import io.element.android.libraries.htmlrenderer.api.ParagraphNode
import io.element.android.libraries.htmlrenderer.api.QuoteNode
import io.element.android.libraries.maths.test.FakeMathsLayout
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.setSafeContent
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.junit.Test
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HtmlMessageContentTest : RobolectricTest() {
    @Test
    fun `inline maths in a quote measures without intrinsic queries`() = verifyMathsQuote(LayoutDirection.Ltr, nestedList = false)

    @Test
    fun `inline maths in an RTL quote reserves the rail on the right`() = verifyMathsQuote(LayoutDirection.Rtl, nestedList = false)

    @Test
    fun `inline maths in a quoted list with a nested quote respects available width`() = verifyMathsQuote(LayoutDirection.Ltr, nestedList = true)

    @Test
    fun `inline maths in an RTL quoted list with a nested quote respects available width`() = verifyMathsQuote(LayoutDirection.Rtl, nestedList = true)

    private fun verifyMathsQuote(direction: LayoutDirection, nestedList: Boolean) = runAndroidComposeUiTest {
        val layout = FakeMathsLayout(widthEm = 20f)
        val paragraph = ParagraphNode(
            text = buildAnnotatedString { appendInlineContent("maths_0", "formula") },
            inlineMaths = persistentMapOf("maths_0" to MathsInlineContent("x", layout)),
        )
        val quoteContent = if (nestedList) {
            ListNode(
                ordered = false,
                startIndex = 1,
                items = persistentListOf(ListItemNode(persistentListOf(QuoteNode(persistentListOf(paragraph))))),
            )
        } else {
            paragraph
        }
        val document = DocumentNode(persistentListOf(QuoteNode(persistentListOf(quoteContent))))
        setSafeContent {
            ElementTheme {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    HtmlMessageContent(document, Modifier.width(240.dp).testTag("message"))
                }
            }
        }

        val message = onNodeWithTag("message").getUnclippedBoundsInRoot()
        val formula = onNodeWithText("formula").getUnclippedBoundsInRoot()
        onNodeWithText("formula").assertIsDisplayed()
        // Each quote reserves 2dp for the rail and 8dp for the gap; the list marker reserves 24dp.
        val startInset = if (nestedList) 44f else 10f
        val actualStartInset = if (direction == LayoutDirection.Ltr) formula.left - message.left else message.right - formula.right
        assertThat(actualStartInset.value).isWithin(0.5f).of(startInset)
        assertThat((formula.right - formula.left).value).isWithin(0.5f).of(240f - startInset)
        assertThat((formula.bottom - formula.top).value).isGreaterThan(0f)
        assertThat(formula.left.value).isAtLeast(message.left.value)
        assertThat(formula.right.value).isAtMost(message.right.value)
    }
}
