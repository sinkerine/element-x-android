/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.threads.impl

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.utils.AppDrawerGestureBlocker
import io.element.android.libraries.designsystem.utils.LocalAppDrawerGestureBlocker
import io.element.android.libraries.ui.strings.CommonStrings
import kotlin.math.abs

@Composable
internal fun ThreadsDrawerLayout(
    isEnabled: Boolean,
    isOpen: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    inbox: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentOnClose by rememberUpdatedState(onClose)
    val currentEnabled by rememberUpdatedState(isEnabled)
    val currentOpen by rememberUpdatedState(isOpen)
    var synchronizing by remember { mutableStateOf(true) }
    var sheetWidth by remember { mutableIntStateOf(0) }
    var pointerDown by remember { mutableStateOf(false) }
    // Retain only a committed inbox through its closing animation. A cancelled opening drag never mounts it.
    var retainInbox by remember { mutableStateOf(isEnabled && isOpen) }
    val blocker = remember { AppDrawerGestureBlocker() }
    val drawerState = rememberDrawerState(
        initialValue = if (isEnabled && isOpen) DrawerValue.Open else DrawerValue.Closed,
        confirmStateChange = { it != DrawerValue.Open || currentEnabled },
    )
    // Keep unsettled emissions so returning to the same anchor after an interrupted animation is reconciled.
    // A drag cancels the animation before settling: currentValue alone still describes the previous anchor.
    LaunchedEffect(drawerState) {
        snapshotFlow {
            val value = drawerState.currentValue
            val anchor = if (value == DrawerValue.Open) 0f else -sheetWidth.toFloat()
            val atAnchor = sheetWidth > 0 && abs(drawerState.currentOffset - anchor) < 0.5f
            val interactionInProgress = pointerDown || drawerState.isAnimationRunning
            val readyToReconcile = !synchronizing && !interactionInProgress && atAnchor
            if (readyToReconcile) value else null
        }.collect { value ->
            when (value) {
                DrawerValue.Open -> if (currentEnabled && !currentOpen) currentOnOpen()
                DrawerValue.Closed -> {
                    if (currentOpen) currentOnClose() else retainInbox = false
                }
                null -> Unit
            }
        }
    }
    LaunchedEffect(isOpen, isEnabled) {
        synchronizing = true
        try {
            if (isEnabled && isOpen) {
                retainInbox = true
                drawerState.open()
            } else {
                if (!isEnabled && isOpen) currentOnClose()
                drawerState.close()
                retainInbox = false
            }
        } finally {
            synchronizing = false
        }
    }
    val title = stringResource(CommonStrings.screen_home_tab_threads)
    ModalNavigationDrawer(
        modifier = modifier.pointerInput(Unit) {
            try {
                awaitPointerEventScope {
                    while (true) {
                        // Observe without consuming so child gestures retain priority.
                        pointerDown = awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }
                    }
                }
            } finally {
                pointerDown = false
            }
        },
        drawerState = drawerState,
        gesturesEnabled = isEnabled && (isOpen || !blocker.isBlocked),
        drawerContent = {
            ModalDrawerSheet(
                Modifier.widthIn(max = 360.dp).onSizeChanged { sheetWidth = it.width }.semantics { paneTitle = title },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, Modifier.weight(1f), style = ElementTheme.typography.fontHeadingSmMedium)
                    IconButton(onClick = onClose) {
                        Icon(CompoundIcons.Close(), stringResource(CommonStrings.action_close))
                    }
                }
                if (isEnabled && (isOpen || retainInbox)) inbox(Modifier.fillMaxSize())
            }
        },
    ) {
        CompositionLocalProvider(LocalAppDrawerGestureBlocker provides blocker, content = content)
    }
    // Compose after the page so this registration wins even when initially open or restored after rotation.
    if (isEnabled && isOpen) BackHandler(onBack = onClose)
}

internal class ThreadsDrawerOpenBooleanPreviewParam : PreviewParameterProvider<Boolean> {
    override val values = sequenceOf(false, true)
}

@PreviewsDayNight
@Composable
internal fun ThreadsDrawerLayoutPreview(@PreviewParameter(ThreadsDrawerOpenBooleanPreviewParam::class) isOpen: Boolean) = ElementPreview {
    ThreadsDrawerLayout(
        isEnabled = true,
        isOpen = isOpen,
        onOpen = {},
        onClose = {},
        inbox = { modifier ->
            Box(modifier) {
                ThreadsInboxView(ThreadsInboxStatePreviewParam().values.first(), {}, {})
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) { Text("Home") }
    }
}
