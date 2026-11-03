/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.threads.api

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

interface ThreadsDrawer {
    /** True when FeatureFlags.CrossRoomThreads and FeatureFlags.Threads are both enabled. */
    val isEnabled: StateFlow<Boolean>
    val isOpen: StateFlow<Boolean>

    /** Identity of the active session visit; null after it ends or resets. Not restored after process death. */
    val visitId: StateFlow<UUID?>

    /** Shows the drawer; starts a triage visit if none is active. No-op when disabled or already open. */
    fun open()

    /** Hides the drawer; ends the visit unless a thread opened from the drawer is still above the origin page. */
    fun close()

    /** A thread was opened from the drawer: hides the drawer and keeps the visit active. */
    fun onThreadOpened()

    /** The origin page is active again (or gone): ends the visit if the drawer is closed. */
    fun onReturnedToOrigin()

    /** Wraps [content] in the modal drawer; [inbox] renders the inbox. [isAvailable] gates the drawer (e.g. FTUE complete). */
    @Composable
    fun Layout(
        isAvailable: Boolean,
        inbox: @Composable (Modifier) -> Unit,
        modifier: Modifier,
        content: @Composable () -> Unit,
    )
}
