/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.threads.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrix.api.room.threads.InboxThread
import io.element.android.libraries.matrix.api.room.threads.InboxThreadKey
import io.element.android.libraries.ui.strings.CommonStrings
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadsInboxView(
    state: ThreadsInboxState,
    onOpen: (InboxThreadKey) -> Unit,
    onOpenInRoom: (InboxThreadKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf<InboxThread?>(null) }
    val clipboard = LocalClipboardManager.current
    val markReadLabel = stringResource(CommonStrings.screen_threads_inbox_mark_read)
    val listState = rememberLazyListState()
    // Unread rows sort first and can arrive after read rows; keep the list at the top until the user drags it.
    var userScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) userScrolled = true }
    }
    val firstKey = state.rows.dataOrNull()?.firstOrNull()?.key
    LaunchedEffect(firstKey) {
        if (!userScrolled && firstKey != null) listState.scrollToItem(0)
    }
    LaunchedEffect(listState, state.rows, state.hasMore) {
        snapshotFlow {
            val layout = listState.layoutInfo
            state.hasMore && state.rows.isSuccess() && listState.isScrollInProgress &&
                layout.totalItemsCount > 2 && (layout.visibleItemsInfo.lastOrNull()?.index ?: 0) >= layout.totalItemsCount - 3
        }.distinctUntilChanged().collect { nearEnd -> if (nearEnd) state.eventSink(ThreadsInboxEvent.LoadMore) }
    }
    Column(modifier.padding(16.dp)) {
        TextButton(enabled = state.rows.isSuccess(), onClick = { state.eventSink(ThreadsInboxEvent.MarkAllAsRead) }) {
            Box(Modifier.padding(end = 8.dp)) {
                Icon(CompoundIcons.Check(), null)
                Icon(CompoundIcons.Check(), null, modifier = Modifier.padding(start = 8.dp))
            }
            Text(stringResource(CommonStrings.screen_threads_inbox_mark_loaded_read))
        }
        if (state.pending.isNotEmpty()) {
            Snackbar(action = {
                TextButton(onClick = { state.eventSink(ThreadsInboxEvent.Undo) }) { Text(stringResource(CommonStrings.action_undo)) }
            }) {
                Text(
                    pluralStringResource(
                        io.element.android.libraries.ui.strings.R.plurals.screen_threads_inbox_marked_read,
                        state.pending.size,
                        state.pending.size
                    )
                )
            }
        }
        if (state.rows.isLoading()) {
            androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(CommonStrings.screen_threads_inbox_loading_more))
        }
        if (state.rows.isFailure() && !state.rows.dataOrNull().isNullOrEmpty()) {
            Snackbar(action = {
                TextButton(onClick = { state.eventSink(ThreadsInboxEvent.RetryLoad) }) { Text(stringResource(CommonStrings.action_retry)) }
            }) { Text(stringResource(CommonStrings.screen_threads_inbox_load_failed)) }
        }
        if (state.failed) {
            Snackbar(action = {
                TextButton(onClick = { state.eventSink(ThreadsInboxEvent.Retry) }) { Text(stringResource(CommonStrings.action_retry)) }
            }) {
                Text(stringResource(CommonStrings.screen_threads_inbox_mark_failed))
            }
        }
        when {
            state.rows.isFailure() && state.rows.dataOrNull().isNullOrEmpty() -> Column {
                Text(stringResource(CommonStrings.screen_threads_inbox_load_failed))
                TextButton(onClick = { state.eventSink(ThreadsInboxEvent.RetryLoad) }) { Text(stringResource(CommonStrings.action_retry)) }
            }
            state.rows.dataOrNull() == null -> ThreadsInboxLoading()
            state.rows.isLoading() && state.rows.dataOrNull().isNullOrEmpty() -> ThreadsInboxLoading()
            state.rows.dataOrNull().isNullOrEmpty() && state.hasMore -> {
                Text(stringResource(CommonStrings.screen_threads_inbox_no_loaded_matches))
                TextButton(onClick = { state.eventSink(ThreadsInboxEvent.LoadMore) }) { Text(stringResource(CommonStrings.action_load_more)) }
            }
            state.rows.dataOrNull().isNullOrEmpty() -> {
                Text(stringResource(CommonStrings.screen_threads_inbox_empty))
                Text(stringResource(CommonStrings.screen_threads_inbox_start_thread_hint))
            }
            else -> LazyColumn(state = listState) {
                items(state.rows.dataOrNull().orEmpty(), key = { it.key.roomId.value + it.key.threadId.value }) { thread ->
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) state.eventSink(ThreadsInboxEvent.MarkAsRead(thread.key))
                            false
                        },
                    )
                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = false,
                        backgroundContent = {
                            // Only revealed while swiping; nothing is drawn behind a settled row.
                            if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                                Row(
                                    Modifier.fillMaxSize().background(ElementTheme.colors.bgAccentRest).padding(horizontal = 24.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        stringResource(CommonStrings.screen_threads_inbox_mark_read),
                                        color = ElementTheme.colors.textOnSolidPrimary,
                                        style = ElementTheme.typography.fontBodyMdMedium,
                                    )
                                    // The swipe reveals from the end, so the icon shows first.
                                    Icon(CompoundIcons.Check(), null, tint = ElementTheme.colors.iconOnSolidPrimary)
                                }
                            }
                        },
                    ) {
                        ThreadsInboxRow(
                            thread = thread,
                            rootPreview = state.summaries[thread.key]?.first.orEmpty(),
                            replyPreview = state.summaries[thread.key]?.second.orEmpty(),
                            visited = thread.key in state.visited,
                            onOpen = { onOpen(thread.key) },
                            onLongClick = { menu = thread },
                            onMarkRead = { state.eventSink(ThreadsInboxEvent.MarkAsRead(thread.key)) },
                            onTouch = { state.eventSink(ThreadsInboxEvent.Touch(thread.key, it)) },
                        )
                    }
                }
                if (state.hasMore && !state.rows.isLoading()) {
                    item {
                        TextButton(onClick = { state.eventSink(ThreadsInboxEvent.LoadMore) }) { Text(stringResource(CommonStrings.action_load_more)) }
                    }
                }
                item { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 96.dp)) }
            }
        }
    }
    menu?.let { thread ->
        ModalBottomSheet(onDismissRequest = { menu = null }) {
            TextButton(onClick = {
                state.eventSink(if (thread.isUnread) ThreadsInboxEvent.MarkAsRead(thread.key) else ThreadsInboxEvent.MarkAsUnread(thread.key))
                menu = null
            }) {
                Text(stringResource(if (thread.isUnread) CommonStrings.screen_threads_inbox_mark_read else CommonStrings.screen_threads_inbox_mark_unread))
            }
            TextButton(onClick = {
                onOpenInRoom(thread.key)
                menu = null
            }) { Text(stringResource(CommonStrings.screen_threads_inbox_open_in_room)) }
            TextButton(onClick = {
                clipboard.setText(AnnotatedString("https://matrix.to/#/${thread.key.roomId.value}/${thread.key.threadId.value}"))
                menu = null
            }) { Text(stringResource(CommonStrings.screen_threads_inbox_copy_link)) }
        }
    }
}
