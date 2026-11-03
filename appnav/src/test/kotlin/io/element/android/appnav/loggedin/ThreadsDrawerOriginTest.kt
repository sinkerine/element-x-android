/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.appnav.loggedin

import com.bumble.appyx.core.navigation.NavKey
import com.bumble.appyx.core.state.MutableSavedStateMapImpl
import com.bumble.appyx.navmodel.backstack.BackStack
import com.bumble.appyx.navmodel.backstack.active
import com.bumble.appyx.navmodel.backstack.operation.pop
import com.bumble.appyx.navmodel.backstack.operation.push
import com.bumble.appyx.navmodel.backstack.operation.replace
import com.google.common.truth.Truth.assertThat
import io.element.android.appnav.LoggedInFlowNode.NavTarget
import io.element.android.appnav.room.RoomNavigationTarget
import io.element.android.appnav.room.replaceTriageThread
import io.element.android.features.threads.impl.DefaultThreadsDrawer
import io.element.android.features.threads.test.FakeThreadsTriageQueue
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.featureflag.test.FakeFeatureFlagService
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.core.toRoomIdOrAlias
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadsDrawerOriginTest : RobolectricTest() {
    @Test
    fun `active origin has returned`() {
        val stack = backstack()
        assertThat(stack.elements.value.hasReturnedTo(requireNotNull(stack.active).key)).isTrue()
    }

    @Test
    fun `pushing thread preserves Settings origin until Back`() {
        val stack = backstack(NavTarget.Settings())
        val origin = requireNotNull(stack.active).key
        stack.push(thread())
        assertThat(stack.elements.value.hasReturnedTo(origin)).isFalse()
        stack.pop()
        assertThat(stack.elements.value.hasReturnedTo(origin)).isTrue()
        assertThat(stack.active?.key).isEqualTo(origin)
    }

    @Test
    fun `Up next keeps original room below replacement thread`() {
        val stack = backstack(NavTarget.Room(RoomId("!origin:example.org").toRoomIdOrAlias()))
        val origin = requireNotNull(stack.active).key
        stack.push(thread())
        repeat(2) { index ->
            stack.replaceTriageThread(RoomId("!next$index:example.org"), ThreadId("\$next$index"), EventId("\$unread$index"))
            assertThat(stack.elements.value.hasReturnedTo(origin)).isFalse()
        }
        stack.pop()
        assertThat(stack.active?.key).isEqualTo(origin)
        assertThat(stack.elements.value.hasReturnedTo(origin)).isTrue()
    }

    @Test
    fun `removed origin has returned even when a different page is active`() {
        val stack = backstack()
        val origin = requireNotNull(stack.active).key
        stack.replace(NavTarget.Settings())
        // A replacing element may still animate out; a completely removed origin must also end the visit.
        val elements = stack.elements.value.filterNot { it.key == origin }
        assertThat(elements.hasReturnedTo(origin)).isTrue()
    }

    @Test
    fun `saved navigation key still identifies origin after backstack restoration`() {
        val stack = backstack(NavTarget.Settings())
        val origin = requireNotNull(stack.active).key
        stack.push(thread())
        val savedState = MutableSavedStateMapImpl { true }
        stack.saveInstanceState(savedState)
        savedState["threads_drawer_origin"] = origin
        val restored = BackStack<NavTarget>(initialElement = NavTarget.Home, savedStateMap = savedState)
        @Suppress("UNCHECKED_CAST")
        val restoredOrigin = savedState["threads_drawer_origin"] as NavKey<NavTarget>
        assertThat(restored.elements.value.hasReturnedTo(restoredOrigin)).isFalse()
        restored.replaceTriageThread(RoomId("!next:example.org"), ThreadId("\$next"), null)
        restored.pop()
        assertThat(restored.active?.key).isEqualTo(restoredOrigin)
        assertThat(restored.elements.value.hasReturnedTo(restoredOrigin)).isTrue()
    }

    @Test
    fun `origin identity uses navigation key rather than target equality`() {
        val stack = backstack()
        val origin = NavKey<NavTarget>(NavTarget.Home)
        assertThat(stack.elements.value.hasReturnedTo(origin)).isTrue()
    }

    @Test
    fun `flag reset and new Settings visit cannot inherit old Home origin even when observer misses reset`() = runTest {
        val flags = FakeFeatureFlagService()
        val queue = FakeThreadsTriageQueue()
        val drawer = drawer(queue, flags)
        val stack = backstack()
        val ownership = ThreadsDrawerOrigin(drawer, stack, null)
        ownership.observe(backgroundScope)
        runCurrent()
        drawer.open()
        ownership.openThread(thread())
        runCurrent()
        stack.push(NavTarget.Settings())
        val settings = requireNotNull(stack.active).key
        flags.setFeatureEnabled(FeatureFlags.CrossRoomThreads, false)
        assertThat(drawer.visitId.value).isNull()
        flags.setFeatureEnabled(FeatureFlags.CrossRoomThreads, true)
        // Do not run the navigation observer between reset and the new visit: StateFlow may conflate them.
        drawer.open()
        ownership.openThread(thread())
        runCurrent()
        assertThat(queue.startVisitCount).isEqualTo(2)
        assertThat(queue.endVisitCount).isEqualTo(1)
        drawer.open()
        drawer.close()
        assertThat(queue.startVisitCount).isEqualTo(2)
        assertThat(queue.endVisitCount).isEqualTo(1)
        stack.pop()
        runCurrent()
        assertThat(stack.active?.key).isEqualTo(settings)
        assertThat(drawer.isOpen.value).isFalse()
        assertThat(drawer.visitId.value).isNull()
        assertThat(queue.endVisitCount).isEqualTo(2)
        stack.pop()
        runCurrent()
        assertThat(queue.endVisitCount).isEqualTo(2)
    }

    @Test
    fun `fresh controller restore discards saved origin before opening a new Settings visit`() = runTest {
        val oldDrawer = drawer(FakeThreadsTriageQueue())
        val stack = backstack()
        val ownership = ThreadsDrawerOrigin(oldDrawer, stack, null)
        oldDrawer.open()
        ownership.openThread(thread())
        stack.push(NavTarget.Settings())
        val state = MutableSavedStateMapImpl { true }
        stack.saveInstanceState(state)
        ownership.saveInstanceState(state)
        val restoredStack = BackStack<NavTarget>(initialElement = NavTarget.Home, savedStateMap = state)
        val settings = requireNotNull(restoredStack.active).key
        val queue = FakeThreadsTriageQueue()
        val freshDrawer = drawer(queue)
        val restored = ThreadsDrawerOrigin(freshDrawer, restoredStack, state)
        restored.observe(backgroundScope)
        runCurrent()
        freshDrawer.open()
        restored.openThread(thread())
        runCurrent()
        assertThat(queue.endVisitCount).isEqualTo(0)
        restoredStack.pop()
        runCurrent()
        assertThat(restoredStack.active?.key).isEqualTo(settings)
        assertThat(freshDrawer.visitId.value).isNull()
        assertThat(queue.startVisitCount).isEqualTo(1)
        assertThat(queue.endVisitCount).isEqualTo(1)
    }

    @Test
    fun `configuration restore retains live origin through Up next and ends once on return`() = runTest {
        val queue = FakeThreadsTriageQueue()
        val drawer = drawer(queue)
        val stack = backstack(NavTarget.Settings())
        val settings = requireNotNull(stack.active).key
        val ownership = ThreadsDrawerOrigin(drawer, stack, null)
        drawer.open()
        ownership.openThread(thread())
        val visit = drawer.visitId.value
        val state = MutableSavedStateMapImpl { true }
        stack.saveInstanceState(state)
        ownership.saveInstanceState(state)
        val restoredStack = BackStack<NavTarget>(initialElement = NavTarget.Home, savedStateMap = state)
        val restored = ThreadsDrawerOrigin(drawer, restoredStack, state)
        // Unconfined observation also checks immediate push/observer ordering.
        restored.observe(CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))
        drawer.open()
        restored.openThread(thread())
        restoredStack.pop()
        restoredStack.replaceTriageThread(RoomId("!next:example.org"), ThreadId("\$next"), null)
        runCurrent()
        assertThat(queue.startVisitCount).isEqualTo(1)
        assertThat(queue.endVisitCount).isEqualTo(0)
        assertThat(drawer.visitId.value).isEqualTo(visit)
        restoredStack.pop()
        runCurrent()
        assertThat(restoredStack.active?.key).isEqualTo(settings)
        assertThat(queue.endVisitCount).isEqualTo(1)
        drawer.onReturnedToOrigin()
        assertThat(queue.endVisitCount).isEqualTo(1)
    }

    @Test
    fun `immediate observer does not end visit while first drawer thread is pushed`() = runTest {
        val queue = FakeThreadsTriageQueue()
        val drawer = drawer(queue)
        val stack = backstack()
        val ownership = ThreadsDrawerOrigin(drawer, stack, null)
        ownership.observe(CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))
        drawer.open()
        ownership.openThread(thread())
        assertThat(queue.startVisitCount).isEqualTo(1)
        assertThat(queue.endVisitCount).isEqualTo(0)
        stack.pop()
        runCurrent()
        assertThat(queue.endVisitCount).isEqualTo(1)
    }

    private fun TestScope.drawer(
        queue: FakeThreadsTriageQueue,
        flags: FakeFeatureFlagService = FakeFeatureFlagService(),
    ): DefaultThreadsDrawer = DefaultThreadsDrawer(
        queue,
        flags,
        FakeMatrixClient(sessionCoroutineScope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))),
    )

    private fun backstack(origin: NavTarget = NavTarget.Home) = BackStack(initialElement = origin, savedStateMap = null)

    private fun thread() = NavTarget.Room(
        RoomId("!thread:example.org").toRoomIdOrAlias(),
        initialElement = RoomNavigationTarget.Thread(ThreadId("\$root"), EventId("\$unread")),
    )
}
