/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.threads.test

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.element.android.features.threads.api.ThreadsDrawer
import kotlinx.coroutines.flow.MutableStateFlow

class FakeThreadsDrawer(
    override val isEnabled: MutableStateFlow<Boolean> = MutableStateFlow(true),
    override val isOpen: MutableStateFlow<Boolean> = MutableStateFlow(false),
) : ThreadsDrawer {
    val calls: MutableList<String> = mutableListOf()

    override fun open() {
        calls += "open"
        if (isEnabled.value) isOpen.value = true
    }

    override fun close() {
        calls += "close"
        isOpen.value = false
    }

    override fun onThreadOpened() {
        calls += "onThreadOpened"
        isOpen.value = false
    }

    override fun onReturnedToOrigin() {
        calls += "onReturnedToOrigin"
    }

    @Composable
    override fun Layout(
        isAvailable: Boolean,
        inbox: @Composable (Modifier) -> Unit,
        modifier: Modifier,
        content: @Composable () -> Unit,
    ) {
        content()
    }
}
