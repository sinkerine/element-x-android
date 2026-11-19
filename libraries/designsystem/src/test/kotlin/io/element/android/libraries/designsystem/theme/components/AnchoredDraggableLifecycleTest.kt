/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.element.android.libraries.designsystem.theme.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.runtime.BroadcastFrameClock
import com.google.common.truth.Truth.assertThat
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AnchoredDraggableLifecycleTest : RobolectricTest() {
    @Test
    fun `untargeted exception clears anchor hint and propagates without settling`() = runTest {
        val state = state()
        val failure = UntargetedFailure()
        var observed: Throwable? = null
        try {
            state.anchoredDrag {
                state.updateAnchors(DraggableAnchors {
                    0 at 0f
                    1 at 120f
                }, newTarget = 1)
                dragTo(40f)
                throw failure
            }
        } catch (caught: IllegalStateException) {
            observed = caught
        }
        assertThat(observed).isSameInstanceAs(failure)
        assertThat(state.isAnimationRunning).isFalse()
        assertThat(state.settledValue).isEqualTo(0)
        assertThat(state.offset).isEqualTo(40f)
    }

    @Test
    fun `targeted exception clears target and propagates without settling`() = runTest {
        val state = state()
        val failure = TargetedFailure()
        var observed: Throwable? = null
        try {
            state.anchoredDrag(targetValue = 1) { _, _ ->
                dragTo(40f)
                throw failure
            }
        } catch (caught: IllegalArgumentException) {
            observed = caught
        }
        assertThat(observed).isSameInstanceAs(failure)
        assertThat(state.isAnimationRunning).isFalse()
        assertThat(state.settledValue).isEqualTo(0)
        assertThat(state.offset).isEqualTo(40f)
    }

    @Test
    fun `targeted cancellation preserves partial offset and prior settlement`() = runTest {
        val state = state()
        val entered = CompletableDeferred<Unit>()
        val owner = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            state.anchoredDrag(targetValue = 1) { _, _ ->
                dragTo(40f)
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        assertThat(state.isAnimationRunning).isTrue()
        owner.cancelAndJoin()
        assertThat(owner.isCancelled).isTrue()
        assertThat(state.isAnimationRunning).isFalse()
        assertThat(state.settledValue).isEqualTo(0)
        assertThat(state.offset).isEqualTo(40f)
    }

    @Test
    fun `higher priority successor retains its target after previous owner exits`() = runTest {
        val state = state()
        state.updateAnchors(DraggableAnchors {
            0 at 0f
            1 at 100f
            2 at 200f
        })
        val previous = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            state.anchoredDrag(targetValue = 1) { _, _ -> awaitCancellation() }
        }
        val entered = CompletableDeferred<Unit>()
        val successor = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            state.anchoredDrag(targetValue = 2, dragPriority = MutatePriority.UserInput) { _, _ ->
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        try {
            entered.await()
            previous.join()
            assertThat(previous.isCancelled).isTrue()
            assertThat(successor.isActive).isTrue()
            assertThat(state.targetValue).isEqualTo(2)
            assertThat(state.isAnimationRunning).isTrue()
            assertThat(state.settledValue).isEqualTo(0)
        } finally {
            successor.cancelAndJoin()
            previous.cancelAndJoin()
        }
        assertThat(state.isAnimationRunning).isFalse()
        assertThat(state.settledValue).isEqualTo(0)
    }

    @Test
    fun `successful targeted mutation retains normal settlement`() = runTest {
        val state = state()
        state.anchoredDrag(targetValue = 1) { anchors, target -> dragTo(anchors.positionOf(target)) }
        assertThat(state.currentValue).isEqualTo(1)
        assertThat(state.settledValue).isEqualTo(1)
        assertThat(state.offset).isEqualTo(100f)
        assertThat(state.isAnimationRunning).isFalse()
    }

    @Test
    fun `user drag interrupts a running animation without completing its target`() = runTest {
        val state = state()
        val clock = BroadcastFrameClock()
        val animation = backgroundScope.launch(clock, start = CoroutineStart.UNDISPATCHED) {
            state.animateTo(1, tween(durationMillis = 1000, easing = LinearEasing))
        }
        try {
            assertThat(clock.hasAwaiters).isTrue()
            clock.sendFrame(0L)
            runCurrent()
            clock.sendFrame(250_000_000L)
            runCurrent()
            assertThat(animation.isActive).isTrue()
            assertThat(state.isAnimationRunning).isTrue()
            assertThat(state.offset).isGreaterThan(0f)
            assertThat(state.offset).isLessThan(100f)
            assertThat(state.settledValue).isEqualTo(0)

            state.anchoredDrag(dragPriority = MutatePriority.UserInput) { dragTo(45f) }
            animation.join()
            assertThat(animation.isCancelled).isTrue()
            assertThat(state.offset).isEqualTo(45f)
            assertThat(state.settledValue).isEqualTo(0)
            assertThat(state.isAnimationRunning).isFalse()
        } finally {
            animation.cancelAndJoin()
        }
    }

    private fun state(): AnchoredDraggableState<Int> = AnchoredDraggableState(initialValue = 0).apply {
        updateAnchors(DraggableAnchors {
            0 at 0f
            1 at 100f
        })
    }

    // Keep identity assertions independent of coroutine stack-trace recovery's exception copying.
    private class UntargetedFailure : IllegalStateException("Synthetic mutation failure"), CopyableThrowable<UntargetedFailure> {
        override fun createCopy(): UntargetedFailure? = null
    }

    private class TargetedFailure : IllegalArgumentException("Synthetic targeted mutation failure"), CopyableThrowable<TargetedFailure> {
        override fun createCopy(): TargetedFailure? = null
    }
}
