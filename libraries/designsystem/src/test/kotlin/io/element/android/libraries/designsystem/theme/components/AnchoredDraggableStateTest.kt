/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.designsystem.theme.components

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import com.google.common.truth.Truth.assertThat
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AnchoredDraggableStateTest : RobolectricTest() {
    @Test
    fun `finishing a drag after anchors change clears the animation marker`() = runTest {
        val state = AnchoredDraggableState(initialValue = 0)
        state.updateAnchors(DraggableAnchors {
            0 at 0f
            1 at 100f
        })

        state.anchoredDrag {
            // A layout update arrives while the gesture owns the mutation lock.
            state.updateAnchors(DraggableAnchors {
                0 at 0f
                1 at 120f
            }, newTarget = 1)
            dragTo(120f)
        }

        assertThat(state.currentValue).isEqualTo(1)
        assertThat(state.settledValue).isEqualTo(1)
        assertThat(state.isAnimationRunning).isFalse()
    }

    @Test
    fun `cancelling a drag retires its anchor hint without settling`() = runTest {
        val state = AnchoredDraggableState(initialValue = 0)
        state.updateAnchors(DraggableAnchors {
            0 at 0f
            1 at 100f
        })
        val updated = CompletableDeferred<Unit>()
        val drag = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            state.anchoredDrag {
                state.updateAnchors(DraggableAnchors {
                    0 at 0f
                    1 at 120f
                }, newTarget = 1)
                updated.complete(Unit)
                awaitCancellation()
            }
        }
        updated.await()
        assertThat(state.isAnimationRunning).isTrue()
        drag.cancelAndJoin()
        assertThat(state.settledValue).isEqualTo(0)
        assertThat(state.isAnimationRunning).isFalse()
    }

    @Test
    fun `rejected targeted mutation cannot clear the current owners animation marker`() = runTest {
        val state = AnchoredDraggableState(initialValue = 0)
        state.updateAnchors(DraggableAnchors {
            0 at 0f
            1 at 100f
        })
        val owner = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            state.anchoredDrag(targetValue = 1, dragPriority = MutatePriority.PreventUserInput) { _, _ ->
                awaitCancellation()
            }
        }
        try {
            assertThat(state.isAnimationRunning).isTrue()
            var rejected = false
            try {
                state.anchoredDrag(targetValue = 1, dragPriority = MutatePriority.Default) { _, _ ->
                    error("A lower-priority operation must not enter")
                }
            } catch (_: CancellationException) {
                rejected = true
            }
            assertThat(rejected).isTrue()
            assertThat(owner.isActive).isTrue()
            assertThat(state.isAnimationRunning).isTrue()
        } finally {
            owner.cancelAndJoin()
        }
    }
}
