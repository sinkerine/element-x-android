/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.appnav.loggedin

import com.bumble.appyx.core.navigation.NavKey
import com.bumble.appyx.core.state.MutableSavedStateMap
import com.bumble.appyx.core.state.SavedStateMap
import com.bumble.appyx.navmodel.backstack.BackStack
import com.bumble.appyx.navmodel.backstack.BackStackElement
import com.bumble.appyx.navmodel.backstack.active
import com.bumble.appyx.navmodel.backstack.operation.push
import io.element.android.features.threads.api.ThreadsDrawer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.UUID

/** True when [origin] is the active element again, or no longer in the back stack. */
internal fun <T : Any> List<BackStackElement<T>>.hasReturnedTo(origin: NavKey<T>): Boolean =
    none { it.key == origin } || lastOrNull { it.targetState == BackStack.State.ACTIVE }?.key == origin

/** Associates navigation ownership with the retained controller visit, never with a later visit. */
internal class ThreadsDrawerOrigin<T : Any>(
    private val drawer: ThreadsDrawer,
    private val backstack: BackStack<T>,
    savedState: SavedStateMap?,
) {
    @Suppress("UNCHECKED_CAST")
    private var origin = savedState?.get("threads_drawer_origin") as? NavKey<T>
    private var visitId = savedState?.get("threads_drawer_origin_visit") as? UUID

    init {
        invalidateStaleOrigin()
    }

    fun saveInstanceState(state: MutableSavedStateMap) {
        invalidateStaleOrigin()
        origin?.let {
            state["threads_drawer_origin"] = it
            state["threads_drawer_origin_visit"] = visitId
        }
    }

    fun openThread(target: T) {
        invalidateStaleOrigin()
        val currentVisit = drawer.visitId.value
        val currentOrigin = origin ?: backstack.active?.key
        drawer.onThreadOpened()
        // Push before publishing a new origin: an immediate observer must not see it still active below the thread.
        backstack.push(target)
        if (currentVisit != null && currentVisit == drawer.visitId.value) {
            origin = currentOrigin
            visitId = currentVisit
        }
    }

    fun observe(scope: CoroutineScope) {
        invalidateStaleOrigin()
        if (origin == null) drawer.onReturnedToOrigin()
        combine(backstack.elements, drawer.visitId) { _, _ -> }
            .onEach {
                // Read current values together, rather than a possibly conflated or queued snapshot of either flow.
                invalidateStaleOrigin()
                if (origin?.let(backstack.elements.value::hasReturnedTo) == true) {
                    origin = null
                    visitId = null
                    drawer.onReturnedToOrigin()
                }
            }
            .launchIn(scope)
    }

    private fun invalidateStaleOrigin() {
        if (visitId == null || visitId != drawer.visitId.value) {
            origin = null
            visitId = null
        }
    }
}
