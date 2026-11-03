/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.home.impl

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.testtags.TestTags
import io.element.android.tests.testutils.EventsRecorder
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Test

class HomeViewTest : RobolectricTest() {
    @Test
    fun `Threads button is absent when disabled and toolbar has only Chats and Spaces`() = runAndroidComposeUiTest<ComponentActivity> {
        setContent { Home(aHomeState(showThreads = false)) }
        onNodeWithTag(TestTags.homeThreadsButton.value).assertDoesNotExist()
        onAllNodesWithContentDescription("Threads").assertCountEquals(0)
        onNodeWithContentDescription("Chats").assertIsDisplayed()
        onNodeWithContentDescription("Spaces").assertIsDisplayed()
    }

    @Test
    fun `Threads has exactly one opener and tapping it emits the drawer event`() = runAndroidComposeUiTest<ComponentActivity> {
        val events = EventsRecorder<HomeEvent>()
        setContent { Home(aHomeState(showThreads = true, eventSink = events)) }
        onAllNodesWithContentDescription("Threads").assertCountEquals(1)
        onNodeWithTag(TestTags.homeThreadsButton.value).performClick()
        events.assertList(listOf(HomeEvent.OpenThreadsDrawer))
        onNodeWithContentDescription("Chats").assertIsDisplayed()
        onNodeWithContentDescription("Spaces").assertIsDisplayed()
    }

    @Test
    fun `Threads opener remains available on Spaces`() = runAndroidComposeUiTest<ComponentActivity> {
        val events = EventsRecorder<HomeEvent>()
        setContent {
            Home(aHomeState(showThreads = true, currentHomeNavigationBarItem = HomeNavigationBarItem.Spaces, eventSink = events))
        }
        onNodeWithTag(TestTags.homeThreadsButton.value).performClick()
        events.assertList(listOf(HomeEvent.OpenThreadsDrawer))
        onNodeWithContentDescription("Create space").assertIsDisplayed()
    }

    @Composable
    private fun Home(state: HomeState) = ElementPreview {
        HomeView(
            homeState = state,
            onRoomClick = { _, _ -> },
            onSettingsClick = {},
            onSetUpRecoveryClick = {},
            onConfirmRecoveryKeyClick = {},
            onStartChatClick = {},
            onCreateSpaceClick = {},
            onRoomSettingsClick = {},
            onMenuActionClick = {},
            onReportRoomClick = {},
            onDeclineInviteAndBlockUser = {},
            acceptDeclineInviteView = {},
            leaveRoomView = {},
        )
    }
}
