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
import java.util.UUID

class FakeThreadsDrawer(
    override val isEnabled: MutableStateFlow<Boolean> = MutableStateFlow(true),
    override val isOpen: MutableStateFlow<Boolean> = MutableStateFlow(false),
    override val visitId: MutableStateFlow<UUID?> = MutableStateFlow(null),
) : ThreadsDrawer {
    val calls: MutableList<String> = mutableListOf()
    private var threadOnStack = false

    override fun open() {
        calls += "open"
        if (isEnabled.value) {
            if (visitId.value == null) visitId.value = UUID.randomUUID()
            isOpen.value = true
        }
    }

    override fun close() {
        calls += "close"
        isOpen.value = false
        if (!threadOnStack) visitId.value = null
    }

    override fun onThreadOpened() {
        calls += "onThreadOpened"
        if (visitId.value == null) return
        isOpen.value = false
        threadOnStack = true
    }

    override fun onReturnedToOrigin() {
        calls += "onReturnedToOrigin"
        if (!threadOnStack) return
        threadOnStack = false
        if (!isOpen.value) visitId.value = null
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
