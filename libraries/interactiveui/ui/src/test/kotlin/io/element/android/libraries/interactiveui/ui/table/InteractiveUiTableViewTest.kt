/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.libraries.interactiveui.ui.table

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.setSafeContent
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Test

class InteractiveUiTableViewTest : RobolectricTest() {
    private val columns = persistentListOf(
        InteractiveUiTableColumn("carrier", "Carrier", false, false),
        InteractiveUiTableColumn("price", "Price (£)", true, true)
    )
    private val rows = persistentListOf(
        persistentListOf<InteractiveUiCell>(InteractiveUiCell.Text("DHL"), InteractiveUiCell.Number(24.0)),
        persistentListOf<InteractiveUiCell>(InteractiveUiCell.Text("Royal Mail"), InteractiveUiCell.Number(18.5)),
        persistentListOf<InteractiveUiCell>(InteractiveUiCell.Text("DHL"), InteractiveUiCell.Number(24.0)),
        persistentListOf<InteractiveUiCell>(InteractiveUiCell.Empty, InteractiveUiCell.Empty),
    )

    @Test
    fun `fixture prices sort numerically and ties stay stable in both directions`() {
        assertThat(sortedRowIndices(rows, 1, true, false)).containsExactly(3, 1, 0, 2).inOrder()
        assertThat(sortedRowIndices(rows, 1, true, true)).containsExactly(0, 2, 1, 3).inOrder()
        assertThat(sortedRowIndices(rows, 0, false, false)).containsExactly(3, 0, 2, 1).inOrder()
        assertThat(sortedRowIndices(rows, 0, false, true)).containsExactly(1, 0, 2, 3).inOrder()
        assertThat(sortedRowIndices(listOf(listOf(InteractiveUiCell.Number(-0.0)), listOf(InteractiveUiCell.Number(0.0))), 0, true, true))
            .containsExactly(0, 1)
            .inOrder()
    }

    @Test
    fun `JS display keeps meaningful fixture and extreme values`() {
        assertThat(displayNumber(18.5)).isEqualTo("18.5")
        assertThat(displayNumber(24.0)).isEqualTo("24")
        assertThat(displayNumber(-0.0)).isEqualTo("0")
        assertThat(displayNumber(1e-7)).isEqualTo("1e-7")
        assertThat(displayNumber(1e21)).isEqualTo("1e+21")
        assertThat(displayNumber(Double.MIN_VALUE)).isEqualTo("5e-324")
    }

    @Test
    fun `header requests sort and announces caller owned direction`() = runAndroidComposeUiTest {
        val sort = mutableStateOf<Pair<String, Boolean>?>(null)
        setSafeContent {
            ElementPreview {
                InteractiveUiTableView(
                    "Carrier prices",
                    columns,
                    rows,
                    sort.value,
                    true,
                    true,
            { key -> sort.value = key to (sort.value?.first == key && sort.value?.second == false) }
                )
            }
        }
        onNodeWithText("Carrier").performClick()
        onNodeWithText("Carrier ↑").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Sorted ascending"))
        onNodeWithText("Carrier ↑").performClick()
        onNodeWithText("Carrier ↓").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Sorted descending"))
    }

    @Test
    fun `only first fifty sorted rows render until show all`() = runAndroidComposeUiTest {
        val many = (0..50).map { persistentListOf<InteractiveUiCell>(InteractiveUiCell.Text("Carrier $it")) }.toImmutableList()
        setSafeContent {
            ElementPreview {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    InteractiveUiTableView(
                        "Carriers",
                        persistentListOf(columns.first()),
                        many,
                        null,
                        false,
                        false,
                        {}
                    )
                }
            }
        }
        onNodeWithText("Carrier 50").assertDoesNotExist()
        onNodeWithText("Show all 51 rows").performScrollTo().performClick()
        onNodeWithText("Carrier 50").performScrollTo().assertIsDisplayed()
        onNodeWithText("Show all 51 rows").assertDoesNotExist()
    }

    @Test
    fun `sorting uses all rows before choosing the first fifty`() = runAndroidComposeUiTest {
        val many = (0..50).map { index ->
            persistentListOf<InteractiveUiCell>(InteractiveUiCell.Text("Carrier $index"), InteractiveUiCell.Number(index.toDouble()))
        }.toImmutableList()
        setSafeContent {
            ElementPreview {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    InteractiveUiTableView("Carriers", columns, many, "price" to true, true, true, {})
                }
            }
        }
        onNodeWithText("Carrier 50").assertIsDisplayed()
        onNodeWithText("Carrier 0").assertDoesNotExist()
        onNodeWithText("Show all 51 rows").performScrollTo().performClick()
        onNodeWithText("Carrier 0").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `disabled sort headers retain state and block changes`() = runAndroidComposeUiTest {
        setSafeContent {
            ElementPreview {
                InteractiveUiTableView(
                    null,
                    columns,
                    rows,
                    "carrier" to false,
                    true,
                    false,
                    { error("Disabled header invoked") }
                )
            }
        }
        onNodeWithText("Carrier ↑").assertIsNotEnabled()
        onNodeWithText("Carrier ↑").performClick()
        onNodeWithText("Data table").assertIsDisplayed()
    }
}
