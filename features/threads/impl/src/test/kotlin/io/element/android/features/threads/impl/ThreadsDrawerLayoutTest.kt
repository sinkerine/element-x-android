/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.threads.impl

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.utils.BlockAppDrawerGestures
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.setSafeContent
import org.junit.Test
import org.robolectric.annotation.Config

@Config(qualifiers = "w450dp-h800dp")
class ThreadsDrawerLayoutTest : RobolectricTest() {
    @Test
    fun `inbox mounts only when committed and unmounts after closing`() = runAndroidComposeUiTest {
        val fixture = Fixture()
        setDrawer(fixture)
        runOnIdle { assertThat(fixture.inboxMounts).isEqualTo(0) }
        runOnIdle { fixture.open.value = true }
        onNodeWithText("Threads").assertIsDisplayed()
        onNodeWithText("Inbox").assertIsDisplayed()
        runOnIdle { assertThat(fixture.inboxMounts).isEqualTo(1) }
        onNodeWithContentDescription("Close").performClick()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        runOnIdle {
            assertThat(fixture.closeCalls).isAtLeast(1)
            assertThat(fixture.inboxDisposals).isEqualTo(1)
        }
    }

    @Test
    fun `controller transitions do not echo callbacks or reopen when reversed mid-animation`() = runAndroidComposeUiTest {
        val fixture = Fixture(initiallyOpen = true)
        setDrawer(fixture)
        runOnIdle {
            assertThat(fixture.openCalls).isEqualTo(0)
            assertThat(fixture.closeCalls).isEqualTo(0)
        }
        mainClock.autoAdvance = false
        runOnIdle { fixture.open.value = false }
        mainClock.advanceTimeBy(48)
        runOnIdle { fixture.open.value = true }
        mainClock.advanceTimeBy(48)
        // Models selecting a thread as the drawer is opening: the controller immediately hides it.
        runOnIdle { fixture.open.value = false }
        mainClock.advanceTimeBy(1_000)
        mainClock.autoAdvance = true
        runOnIdle {
            assertThat(fixture.open.value).isFalse()
            assertThat(fixture.openCalls).isEqualTo(0)
            assertThat(fixture.closeCalls).isEqualTo(0)
            assertThat(fixture.inboxDisposals).isEqualTo(1)
        }
        runOnIdle { fixture.open.value = true }
        onNodeWithText("Inbox").assertIsDisplayed()
        runOnIdle {
            assertThat(fixture.openCalls).isEqualTo(0)
            assertThat(fixture.closeCalls).isEqualTo(0)
        }
    }

    @Test
    fun `closing drag interrupts programmatic opening and disposes inbox exactly once`() = runAndroidComposeUiTest {
        val fixture = Fixture()
        setDrawer(fixture)
        waitForIdle()
        mainClock.autoAdvance = false
        runOnIdle { fixture.open.value = true }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        mainClock.advanceTimeBy(96)
        runOnIdle {
            assertThat(fixture.open.value).isTrue()
            assertThat(fixture.inboxMounts).isEqualTo(1)
            assertThat(fixture.inboxDisposals).isEqualTo(0)
        }
        val inboxLeft = onNodeWithTag("inbox").getUnclippedBoundsInRoot().left.value
        assertThat(inboxLeft).isGreaterThan(-360f)
        assertThat(inboxLeft).isLessThan(0f)
        onRoot().performTouchInput {
            down(Offset(200f, 40f))
            moveTo(Offset(1f, 40f), delayMillis = 200)
        }
        runOnIdle {
            assertThat(fixture.inboxDisposals).isEqualTo(0)
            assertThat(fixture.open.value).isTrue()
        }
        onRoot().performTouchInput { up() }
        mainClock.advanceTimeBy(1_000)
        mainClock.autoAdvance = true
        onNodeWithText("Threads").assertIsNotDisplayed()
        runOnIdle {
            assertThat(fixture.open.value).isFalse()
            assertThat(fixture.openCalls).isEqualTo(0)
            assertThat(fixture.closeCalls).isAtLeast(1)
            assertThat(fixture.inboxMounts).isEqualTo(1)
            assertThat(fixture.inboxDisposals).isEqualTo(1)
        }
    }

    @Test
    fun `closing drag interrupts programmatic closing and disposes inbox exactly once`() = runAndroidComposeUiTest {
        val fixture = Fixture(initiallyOpen = true)
        setDrawer(fixture)
        waitForIdle()
        mainClock.autoAdvance = false
        runOnIdle { fixture.open.value = false }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        mainClock.advanceTimeBy(48)
        runOnIdle {
            assertThat(fixture.inboxMounts).isEqualTo(1)
            assertThat(fixture.inboxDisposals).isEqualTo(0)
        }
        val inboxLeft = onNodeWithTag("inbox").getUnclippedBoundsInRoot().left.value
        assertThat(inboxLeft).isGreaterThan(-360f)
        assertThat(inboxLeft).isLessThan(0f)
        onRoot().performTouchInput {
            down(Offset(200f, 40f))
            moveTo(Offset(1f, 40f), delayMillis = 200)
        }
        runOnIdle { assertThat(fixture.inboxDisposals).isEqualTo(0) }
        onRoot().performTouchInput { up() }
        mainClock.advanceTimeBy(1_000)
        mainClock.autoAdvance = true
        onNodeWithText("Threads").assertIsNotDisplayed()
        runOnIdle {
            assertThat(fixture.open.value).isFalse()
            assertThat(fixture.openCalls).isEqualTo(0)
            assertThat(fixture.closeCalls).isEqualTo(0)
            assertThat(fixture.inboxMounts).isEqualTo(1)
            assertThat(fixture.inboxDisposals).isEqualTo(1)
        }
    }

    @Test
    fun `Back wins over a page handler including initially open drawer`() = runAndroidComposeUiTest {
        val fixture = Fixture(initiallyOpen = true)
        var pageBacks = 0
        setDrawer(fixture) { BackHandler { pageBacks++ } }
        waitForIdle()
        runOnIdle { activity!!.onBackPressedDispatcher.onBackPressed() }
        runOnIdle {
            assertThat(pageBacks).isEqualTo(0)
            assertThat(fixture.closeCalls).isAtLeast(1)
            assertThat(fixture.open.value).isFalse()
        }
        waitForIdle()
        runOnIdle { activity!!.onBackPressedDispatcher.onBackPressed() }
        runOnIdle { assertThat(pageBacks).isEqualTo(1) }
        runOnIdle { fixture.open.value = true }
        waitForIdle()
        runOnIdle { activity!!.onBackPressedDispatcher.onBackPressed() }
        runOnIdle { assertThat(pageBacks).isEqualTo(1) }
    }

    @Test
    fun `unconsumed swipe on content opens drawer`() = runAndroidComposeUiTest {
        val fixture = Fixture()
        setDrawer(fixture)
        onNodeWithTag("page").performTouchInput { swipeRight() }
        runOnIdle {
            assertThat(fixture.openCalls).isAtLeast(1)
            assertThat(fixture.open.value).isTrue()
        }
        onNodeWithText("Inbox").assertIsDisplayed()
    }

    @Test
    fun `cancelled opening drag never composes inbox or requests visit`() = runAndroidComposeUiTest {
        val fixture = Fixture()
        setDrawer(fixture)
        onNodeWithTag("page").performTouchInput {
            down(Offset(30f, centerY))
            moveTo(Offset(110f, centerY), delayMillis = 200)
        }
        runOnIdle { assertThat(fixture.inboxMounts).isEqualTo(0) }
        onNodeWithTag("page").performTouchInput { cancel() }
        runOnIdle {
            assertThat(fixture.openCalls).isEqualTo(0)
            assertThat(fixture.inboxMounts).isEqualTo(0)
        }
    }

    @Test
    fun `blocker prevents opening but allows closing an open drawer`() = runAndroidComposeUiTest {
        val fixture = Fixture()
        setDrawer(fixture) { BlockAppDrawerGestures() }
        onNodeWithTag("page").performTouchInput { swipeRight() }
        runOnIdle { assertThat(fixture.openCalls).isEqualTo(0) }
        runOnIdle { fixture.open.value = true }
        onNodeWithText("Threads").performTouchInput { swipeLeft() }
        runOnIdle { assertThat(fixture.open.value).isFalse() }
    }

    @Test
    fun `disabled drawer cannot open and toggling flags keeps page composition`() = runAndroidComposeUiTest {
        val fixture = Fixture()
        fixture.enabled.value = false
        var pageMounts = 0
        var pageDisposals = 0
        setDrawer(fixture) {
            DisposableEffect(Unit) {
                pageMounts++
                onDispose { pageDisposals++ }
            }
        }
        onNodeWithTag("page").performTouchInput { swipeRight() }
        runOnIdle {
            assertThat(fixture.openCalls).isEqualTo(0)
            fixture.enabled.value = true
        }
        onNodeWithTag("page").performTouchInput { swipeRight() }
        runOnIdle { fixture.enabled.value = false }
        runOnIdle {
            assertThat(fixture.open.value).isFalse()
            assertThat(fixture.inboxDisposals).isEqualTo(1)
            assertThat(pageMounts).isEqualTo(1)
            assertThat(pageDisposals).isEqualTo(0)
        }
    }

    @Test
    fun `child horizontal drag consumes swipe before drawer`() = runAndroidComposeUiTest {
        val fixture = Fixture()
        var dragged = 0f
        setDrawer(fixture) {
            Box(
                Modifier.fillMaxSize().testTag("child").draggable(
                    state = rememberDraggableState { dragged += it },
                    orientation = Orientation.Horizontal,
                )
            )
        }
        onNodeWithTag("child").performTouchInput { swipeRight() }
        runOnIdle {
            assertThat(dragged).isGreaterThan(0f)
            assertThat(fixture.openCalls).isEqualTo(0)
            assertThat(fixture.inboxMounts).isEqualTo(0)
        }
    }

    @Test
    fun `page and inbox retain original touch slop`() = runAndroidComposeUiTest {
        val fixture = Fixture()
        var originalSlop = 0f
        var pageSlop = 0f
        var inboxSlop = 0f
        setSafeContent {
            originalSlop = LocalViewConfiguration.current.touchSlop
            ElementTheme {
                ThreadsDrawerLayout(
                    isEnabled = true,
                    isOpen = fixture.open.value,
                    onOpen = { fixture.open.value = true },
                    onClose = { fixture.open.value = false },
                    inbox = {
                        val slop = LocalViewConfiguration.current.touchSlop
                        SideEffect { inboxSlop = slop }
                        Box(it)
                    },
                ) {
                    val slop = LocalViewConfiguration.current.touchSlop
                    SideEffect { pageSlop = slop }
                }
            }
        }
        runOnIdle {
            assertThat(pageSlop).isEqualTo(originalSlop)
            fixture.open.value = true
        }
        runOnIdle { assertThat(inboxSlop).isEqualTo(originalSlop) }
    }

    @Test
    fun `horizontal pager consumes swipe before drawer`() = runAndroidComposeUiTest {
        val fixture = Fixture()
        var page = 1
        setDrawer(fixture) {
            val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
            val currentPage = pagerState.currentPage
            SideEffect { page = currentPage }
            HorizontalPager(pagerState, Modifier.fillMaxSize().testTag("pager")) {
                Text("Page $it")
            }
        }
        onNodeWithTag("pager").performTouchInput { swipeRight() }
        runOnIdle {
            assertThat(page).isEqualTo(0)
            assertThat(fixture.openCalls).isEqualTo(0)
            assertThat(fixture.inboxMounts).isEqualTo(0)
        }
    }

    @Test
    fun `inbox row drag consumes swipe and scrim tap closes drawer`() = runAndroidComposeUiTest {
        val fixture = Fixture(initiallyOpen = true)
        var dragged = 0f
        setDrawer(fixture, inboxContent = {
            Box(
                Modifier.fillMaxSize().testTag("row").draggable(
                    state = rememberDraggableState { dragged += it },
                    orientation = Orientation.Horizontal,
                )
            )
        })
        onNodeWithTag("row").performTouchInput { swipeLeft() }
        runOnIdle {
            assertThat(dragged).isLessThan(0f)
            assertThat(fixture.open.value).isTrue()
        }
        onRoot().performTouchInput { click(Offset(width - 8f, centerY)) }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        runOnIdle { assertThat(fixture.open.value).isFalse() }
    }

    private class Fixture(initiallyOpen: Boolean = false) {
        val open = mutableStateOf(initiallyOpen)
        val enabled = mutableStateOf(true)
        var openCalls = 0
        var closeCalls = 0
        var inboxMounts = 0
        var inboxDisposals = 0
    }

    private fun AndroidComposeUiTest<ComponentActivity>.setDrawer(
        fixture: Fixture,
        inboxContent: @Composable () -> Unit = { Text("Inbox") },
        pageContent: @Composable () -> Unit = {},
    ) {
        setSafeContent {
            ElementTheme {
                ThreadsDrawerLayout(
                    isEnabled = fixture.enabled.value,
                    isOpen = fixture.open.value,
                    onOpen = {
                        fixture.openCalls++
                        fixture.open.value = true
                    },
                    onClose = {
                        fixture.closeCalls++
                        fixture.open.value = false
                    },
                    inbox = { modifier ->
                        DisposableEffect(Unit) {
                            fixture.inboxMounts++
                            onDispose { fixture.inboxDisposals++ }
                        }
                        Box(modifier.testTag("inbox")) { inboxContent() }
                    },
                    modifier = Modifier.width(420.dp).height(640.dp),
                ) {
                    Box(Modifier.fillMaxSize().testTag("page")) { pageContent() }
                }
            }
        }
    }
}
