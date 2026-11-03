/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.threads.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.features.threads.test.FakeThreadsTriageQueue
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.featureflag.test.FakeFeatureFlagService
import io.element.android.libraries.matrix.test.FakeMatrixClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultThreadsDrawerTest {
    @Test
    fun `open and close start and end once despite repeated calls`() = runTest {
        val (drawer, queue) = drawer()
        drawer.open()
        drawer.open()
        assertThat(drawer.isOpen.value).isTrue()
        assertThat(queue.startVisitCount).isEqualTo(1)
        drawer.close()
        drawer.close()
        drawer.onReturnedToOrigin()
        assertThat(drawer.isOpen.value).isFalse()
        assertThat(queue.endVisitCount).isEqualTo(1)
        drawer.open()
        assertThat(queue.startVisitCount).isEqualTo(2)
    }

    @Test
    fun `thread opened closes drawer and keeps visit until return to origin`() = runTest {
        val (drawer, queue) = drawer()
        drawer.open()
        drawer.onThreadOpened()
        assertThat(drawer.isOpen.value).isFalse()
        assertThat(queue.endVisitCount).isEqualTo(0)
        drawer.close()
        drawer.onReturnedToOrigin()
        drawer.onReturnedToOrigin()
        assertThat(queue.endVisitCount).isEqualTo(1)
    }

    @Test
    fun `reopening and closing during triage retains the same visit`() = runTest {
        val (drawer, queue) = drawer()
        drawer.open()
        drawer.onThreadOpened()
        drawer.open()
        drawer.close()
        assertThat(queue.startVisitCount).isEqualTo(1)
        assertThat(queue.endVisitCount).isEqualTo(0)
        drawer.onReturnedToOrigin()
        assertThat(queue.endVisitCount).isEqualTo(1)
    }

    @Test
    fun `return to origin while drawer open ends only on close`() = runTest {
        val (drawer, queue) = drawer()
        drawer.open()
        drawer.onThreadOpened()
        drawer.open()
        drawer.onReturnedToOrigin()
        assertThat(queue.endVisitCount).isEqualTo(0)
        drawer.close()
        assertThat(queue.endVisitCount).isEqualTo(1)
    }

    @Test
    fun `either disabled flag prevents opening`() = runTest {
        for (flag in listOf(FeatureFlags.Threads, FeatureFlags.CrossRoomThreads)) {
            val flags = FakeFeatureFlagService(mapOf(flag.key to false))
            val (drawer, queue) = drawer(flags)
            drawer.open()
            drawer.onThreadOpened()
            drawer.onReturnedToOrigin()
            assertThat(drawer.isEnabled.value).isFalse()
            assertThat(drawer.isOpen.value).isFalse()
            assertThat(queue.startVisitCount).isEqualTo(0)
            assertThat(queue.endVisitCount).isEqualTo(0)
        }
    }

    @Test
    fun `disabling either flag while open or triaging resets visit once`() = runTest {
        for (flag in listOf(FeatureFlags.Threads, FeatureFlags.CrossRoomThreads)) {
            for (triaging in listOf(false, true)) {
                val flags = FakeFeatureFlagService()
                val (drawer, queue) = drawer(flags)
                drawer.open()
                if (triaging) drawer.onThreadOpened()
                flags.setFeatureEnabled(flag, false)
                runCurrent()
                assertThat(drawer.isOpen.value).isFalse()
                assertThat(queue.endVisitCount).isEqualTo(1)
                drawer.open()
                drawer.close()
                drawer.onReturnedToOrigin()
                assertThat(queue.endVisitCount).isEqualTo(1)
                flags.setFeatureEnabled(flag, true)
                runCurrent()
                drawer.open()
                drawer.close()
                assertThat(queue.startVisitCount).isEqualTo(2)
                assertThat(queue.endVisitCount).isEqualTo(2)
            }
        }
    }

    @Test
    fun `unavailability blocks opening and clears even a visit with a thread on stack`() = runTest {
        for (triaging in listOf(false, true)) {
            val (drawer, queue) = drawer()
            drawer.open()
            if (triaging) drawer.onThreadOpened()
            drawer.setAvailable(false)
            drawer.setAvailable(false)
            drawer.open()
            drawer.onReturnedToOrigin()
            assertThat(drawer.isOpen.value).isFalse()
            assertThat(queue.startVisitCount).isEqualTo(1)
            assertThat(queue.endVisitCount).isEqualTo(1)
            drawer.setAvailable(true)
            drawer.open()
            drawer.close()
            assertThat(queue.startVisitCount).isEqualTo(2)
            assertThat(queue.endVisitCount).isEqualTo(2)
        }
    }

    private fun TestScope.drawer(flags: FakeFeatureFlagService = FakeFeatureFlagService()): Pair<DefaultThreadsDrawer, FakeThreadsTriageQueue> {
        val queue = FakeThreadsTriageQueue()
        val drawer = DefaultThreadsDrawer(queue, flags, FakeMatrixClient(sessionCoroutineScope = backgroundScope))
        runCurrent()
        return drawer to queue
    }
}
