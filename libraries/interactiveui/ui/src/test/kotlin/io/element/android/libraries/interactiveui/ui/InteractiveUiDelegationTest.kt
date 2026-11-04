/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.libraries.interactiveui.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.interactiveui.core.session.InteractiveUiTableSort
import io.element.android.libraries.interactiveui.ui.chart.InteractiveUiChartModelPreviewParam
import io.element.android.libraries.interactiveui.ui.graph.InteractiveUiGraphLayoutPreviewParam
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiRenderNode
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiViewEvent
import io.element.android.libraries.interactiveui.ui.table.InteractiveUiCell
import io.element.android.libraries.interactiveui.ui.table.InteractiveUiTableColumn
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.setSafeContent
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test

class InteractiveUiDelegationTest : RobolectricTest() {
    @Test
    fun `table adapts session sort and emits column key`() = runAndroidComposeUiTest {
        val events = mutableListOf<InteractiveUiViewEvent>()
        val table = InteractiveUiRenderNode.Table(
            "table",
            "Prices",
            persistentListOf(InteractiveUiTableColumn("price", "Price", true, true)),
            persistentListOf(persistentListOf<InteractiveUiCell>(InteractiveUiCell.Number(2.0))),
            InteractiveUiTableSort("price", true),
            true,
            true
        )
        setSafeContent { ElementPreview { InteractiveUiView(viewState(table, events::add), {}, fallback = {}) } }
        onNodeWithText("Price ↓").performClick()
        assertThat(events).containsExactly(InteractiveUiViewEvent.SortTable("table", "price"))
    }

    @Test
    fun `chart delegates data alternative and emits mode event`() = runAndroidComposeUiTest {
        val events = mutableListOf<InteractiveUiViewEvent>()
        val chart = InteractiveUiRenderNode.Chart("chart", InteractiveUiChartModelPreviewParam().values.first(), false, true)
        setSafeContent {
            ElementPreview {
                Column(Modifier.verticalScroll(rememberScrollState())) { InteractiveUiView(viewState(chart, events::add), {}, fallback = {}) }
            }
        }
        onNodeWithText("Data table").performScrollTo().performClick()
        assertThat(events).containsExactly(InteractiveUiViewEvent.SetChartDataMode("chart", true))
    }

    @Test
    fun `graph delegates selection and list mode events`() = runAndroidComposeUiTest {
        val events = mutableListOf<InteractiveUiViewEvent>()
        val graph = InteractiveUiRenderNode.Graph("graph", InteractiveUiGraphLayoutPreviewParam().values.first(), null, true, true)
        setSafeContent {
            ElementPreview {
                Column(Modifier.verticalScroll(rememberScrollState())) { InteractiveUiView(viewState(graph, events::add), {}, fallback = {}) }
            }
        }
        onNodeWithText("Sugar").performScrollTo().performClick()
        onNodeWithText("Relationships as list").performScrollTo().performClick()
        assertThat(events).containsExactly(
            InteractiveUiViewEvent.SelectGraphNode("graph", "sugar"),
            InteractiveUiViewEvent.SetGraphListMode("graph", false),
        ).inOrder()
    }
}
