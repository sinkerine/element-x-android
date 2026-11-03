/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:Suppress("MatchingDeclarationName")

package io.element.android.libraries.designsystem.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** Counts on-screen requests to disable the app drawer's open swipe. */
@Stable
class AppDrawerGestureBlocker {
    private var blockCount by mutableIntStateOf(0)
    val isBlocked: Boolean get() = blockCount > 0

    fun block() {
        blockCount++
    }

    fun unblock() {
        if (blockCount > 0) blockCount--
    }
}

// Screens request blocking through the enclosing app drawer without a feature dependency.
@Suppress("CompositionLocalAllowlist")
val LocalAppDrawerGestureBlocker: ProvidableCompositionLocal<AppDrawerGestureBlocker?> = staticCompositionLocalOf { null }

/** While composed, disables swipe-to-open of the enclosing app drawer. No-op without a provider. */
@Composable
fun BlockAppDrawerGestures() {
    val blocker = LocalAppDrawerGestureBlocker.current
    DisposableEffect(blocker) {
        blocker?.block()
        onDispose { blocker?.unblock() }
    }
}
