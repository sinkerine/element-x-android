/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.threads.impl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.threads.api.ThreadsDrawer
import io.element.android.features.threads.api.ThreadsTriageQueue
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.matrix.api.MatrixClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class)
@Inject
class DefaultThreadsDrawer(
    private val queue: ThreadsTriageQueue,
    featureFlagService: FeatureFlagService,
    client: MatrixClient,
) : ThreadsDrawer {
    override val isEnabled = combine(
        featureFlagService.isFeatureEnabledFlow(FeatureFlags.CrossRoomThreads),
        featureFlagService.isFeatureEnabledFlow(FeatureFlags.Threads),
    ) { crossRoom, threads -> crossRoom && threads }
        .stateIn(client.sessionCoroutineScope, SharingStarted.Eagerly, false)
    private val openState = MutableStateFlow(false)
    override val isOpen = openState.asStateFlow()
    private val visitIdState = MutableStateFlow<UUID?>(null)
    override val visitId = visitIdState.asStateFlow()
    private var threadOnStack = false
    private var available = true

    init {
        client.sessionCoroutineScope.launch {
            isEnabled.collect { enabled -> if (!enabled) resetVisit() }
        }
    }

    override fun open() {
        if (!available || !isEnabled.value || isOpen.value) return
        if (visitId.value == null) {
            queue.startVisit()
            visitIdState.value = UUID.randomUUID()
        }
        openState.value = true
    }

    override fun close() {
        if (!isOpen.value) return
        openState.value = false
        if (!threadOnStack) endVisitIfActive()
    }

    override fun onThreadOpened() {
        if (visitId.value == null) return
        openState.value = false
        threadOnStack = true
    }

    override fun onReturnedToOrigin() {
        if (!threadOnStack) return
        threadOnStack = false
        if (!isOpen.value) endVisitIfActive()
    }

    internal fun setAvailable(isAvailable: Boolean) {
        available = isAvailable
        if (!available) resetVisit()
    }

    private fun resetVisit() {
        openState.value = false
        threadOnStack = false
        endVisitIfActive()
    }

    private fun endVisitIfActive() {
        if (visitId.value == null) return
        visitIdState.value = null
        queue.endVisit()
    }

    @Composable
    override fun Layout(
        isAvailable: Boolean,
        inbox: @Composable (Modifier) -> Unit,
        modifier: Modifier,
        content: @Composable () -> Unit,
    ) {
        val enabled by isEnabled.collectAsState()
        val open by isOpen.collectAsState()
        SideEffect { setAvailable(isAvailable) }
        ThreadsDrawerLayout(
            isEnabled = isAvailable && enabled,
            isOpen = isAvailable && enabled && open,
            onOpen = ::open,
            onClose = ::close,
            inbox = inbox,
            modifier = modifier,
            content = content,
        )
    }
}
