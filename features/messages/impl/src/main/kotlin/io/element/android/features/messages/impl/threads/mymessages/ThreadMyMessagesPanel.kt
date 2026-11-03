/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.threads.mymessages

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.impl.R
import io.element.android.features.messages.impl.timeline.TimelineEvent
import io.element.android.libraries.androidutils.ui.hideKeyboard
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.designsystem.theme.components.LinearProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Surface
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextButton
import io.element.android.libraries.ui.strings.CommonStrings

@Composable
internal fun ThreadMyMessagesLayout(
    state: ThreadMyMessagesState,
    timelineEventSink: (TimelineEvent) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val view = LocalView.current
    PredictiveBackHandler(enabled = state.isOpen) { progress ->
        progress.collect { }
        state.eventSink(ThreadMyMessagesEvent.Close)
    }
    LaunchedEffect(state.isOpen) { if (state.isOpen) view.hideKeyboard() }
    LaunchedEffect(state.pendingJump) {
        state.pendingJump?.let {
            timelineEventSink(TimelineEvent.FocusOnEvent(it))
            state.eventSink(ThreadMyMessagesEvent.JumpConsumed)
        }
    }
    var announcement by remember { mutableStateOf("") }
    LaunchedEffect(state.announcement) {
        state.announcement?.let {
            announcement = ""
            withFrameNanos { }
            announcement = it
            withFrameNanos { }
            state.eventSink(ThreadMyMessagesEvent.AnnouncementConsumed)
        }
    }
    BoxWithConstraints(modifier) {
        val availableHeight = maxHeight
        Column {
            Column(
                Modifier.weight(1f).then(
                    if (state.isOpen) Modifier.consumeWindowInsets(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)) else Modifier
                ),
                content = content
            )
            if (state.isOpen) {
                // Keep some timeline usable even when the available height is smaller than the preferred minimum.
                val panelHeight = maxOf(240.dp, availableHeight * 0.45f).coerceAtMost(availableHeight * 0.7f)
                ThreadMyMessagesPanel(state, Modifier.height(panelHeight).navigationBarsPadding())
            }
        }
        Box(Modifier.size(1.dp).semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = announcement
        })
    }
}

@Composable
internal fun ThreadMyMessagesPanel(state: ThreadMyMessagesState, modifier: Modifier = Modifier) {
    val close = { state.eventSink(ThreadMyMessagesEvent.Close) }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), color = ElementTheme.colors.bgCanvasDefault) {
        Column {
            Box(
                Modifier.fillMaxWidth().height(48.dp).pointerInput(Unit) {
                    var distance = 0f
                    detectVerticalDragGestures(
                        onDragStart = { distance = 0f },
                        onVerticalDrag = { change, amount ->
                            change.consume()
                            distance += amount
                        },
                        onDragEnd = { if (distance > 32.dp.toPx()) close() },
                    )
                },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(32.dp, 4.dp).background(ElementTheme.colors.borderDisabled, RoundedCornerShape(2.dp)))
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val count = if (state.isComplete) {
                    state.items.size.toString()
                } else {
                    stringResource(
                        R.string.screen_thread_my_messages_count_more,
                        state.items.size
                    )
                }
                Text(stringResource(R.string.screen_thread_my_messages_title), style = ElementTheme.typography.fontBodyLgMedium)
                Text(" · $count", Modifier.weight(1f), color = ElementTheme.colors.textSecondary)
                IconButton(onClick = close) { Icon(CompoundIcons.Close(), stringResource(CommonStrings.action_close)) }
            }
            if (state.loadState == ThreadMyMessagesLoadState.Loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f)) {
                items(state.items, key = { it.eventId.value }) { item ->
                    val isCurrent = state.currentEventId == item.eventId
                    val root = stringResource(R.string.a11y_thread_my_messages_root)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .background(if (isCurrent) ElementTheme.colors.bgAccentSubtle else ElementTheme.colors.bgCanvasDefault)
                            .clickable(role = Role.Button) { state.eventSink(ThreadMyMessagesEvent.JumpTo(item.eventId)) }
                            .clearAndSetSemantics {
                                selected = isCurrent
                                role = Role.Button
                                contentDescription = "${item.fullTime}${if (item.isRoot) ", $root" else ""}, ${item.snippet.take(80)}"
                                onClick {
                                    state.eventSink(ThreadMyMessagesEvent.JumpTo(item.eventId))
                                    true
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(
                            Modifier.width(4.dp).height(48.dp).then(if (isCurrent) Modifier.background(ElementTheme.colors.borderAccentSubtle) else Modifier)
                        )
                        Text(
                            item.time,
                            Modifier.padding(horizontal = 12.dp),
                            style = ElementTheme.typography.fontBodySmRegular,
                            color = ElementTheme.colors.textSecondary
                        )
                        Text(item.snippet, Modifier.weight(1f).padding(vertical = 11.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (item.isRoot) {
                            Text(
                                stringResource(R.string.screen_thread_my_messages_root_chip),
                                Modifier.padding(horizontal = 8.dp).background(ElementTheme.colors.bgSubtleSecondary, RoundedCornerShape(4.dp)).padding(4.dp),
                                style = ElementTheme.typography.fontBodySmRegular,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                }
                if (state.items.isEmpty()) {
                    item {
                    Text(stringResource(R.string.screen_thread_my_messages_empty), Modifier.padding(16.dp), color = ElementTheme.colors.textSecondary)
                }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        val footer = when {
                            state.isComplete -> R.string.screen_thread_my_messages_start_of_thread
                            state.isLimitReached -> R.string.screen_thread_my_messages_limit_reached
                            state.loadState == ThreadMyMessagesLoadState.Failed -> R.string.screen_thread_my_messages_load_error
                            else -> R.string.screen_thread_my_messages_loading
                        }
                        Text(
                            stringResource(footer),
                            Modifier.weight(1f),
                            style = ElementTheme.typography.fontBodySmRegular,
                            color = ElementTheme.colors.textSecondary
                        )
                        if (!state.isComplete && !state.isLimitReached && state.loadState == ThreadMyMessagesLoadState.Failed) {
                            TextButton(text = stringResource(CommonStrings.action_retry), onClick = { state.eventSink(ThreadMyMessagesEvent.Retry) })
                        }
                    }
                }
            }
        }
    }
}

@PreviewsDayNight
@Composable
internal fun ThreadMyMessagesPanelPreview(@PreviewParameter(ThreadMyMessagesStateProvider::class) state: ThreadMyMessagesState) = ElementPreview {
    ThreadMyMessagesPanel(state, Modifier.height(360.dp))
}

@PreviewsDayNight
@Composable
internal fun ThreadMyMessagesButtonPreview() = ElementPreview {
    ThreadMyMessagesButton(ThreadMyMessagesStateProvider().values.first().ownAvatar, {})
}
