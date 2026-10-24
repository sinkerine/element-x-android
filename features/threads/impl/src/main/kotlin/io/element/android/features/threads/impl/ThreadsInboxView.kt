/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.threads.impl

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrix.api.room.threads.InboxThread
import io.element.android.libraries.matrix.api.room.threads.InboxThreadKey
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.ui.strings.CommonStrings

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
    Column(modifier.padding(16.dp)) {
        Row {
            TextButton(onClick = { state.eventSink(ThreadsInboxEvent.SelectTab(ThreadsInboxTab.Mine)) }) {
                Text(stringResource(CommonStrings.screen_threads_inbox_tab_mine) + " (${state.mineUnreadCount})")
            }
            TextButton(onClick = { state.eventSink(ThreadsInboxEvent.SelectTab(ThreadsInboxTab.Other)) }) {
                Text(stringResource(CommonStrings.screen_threads_inbox_tab_other) + " (${state.otherUnreadCount})")
            }
        }
        Row {
            ThreadsInboxFilter.entries.forEach { filter ->
                FilterChip(
                    selected = state.filter == filter,
                    onClick = { state.eventSink(ThreadsInboxEvent.SelectFilter(filter)) },
                    label = {
                        Text(stringResource(if (filter ==
                        ThreadsInboxFilter.Unread) {
                            CommonStrings.screen_threads_inbox_filter_unread
                        } else {
                            CommonStrings.screen_threads_inbox_filter_all
                        }))
                    },
                )
            }
            TextButton(onClick = { state.eventSink(ThreadsInboxEvent.MarkAllAsRead) }) {
                Text(stringResource(CommonStrings.screen_threads_inbox_mark_all_read))
            }
        }
        state.latestUnread?.let { thread ->
            Card(onClick = { onOpen(thread.key) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(CommonStrings.screen_threads_inbox_open_latest_unread))
                    Text(thread.roomName.orEmpty(), maxLines = 1)
                }
            }
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
        if (state.failed) {
            Snackbar(action = {
                TextButton(onClick = { state.eventSink(ThreadsInboxEvent.Retry) }) { Text(stringResource(CommonStrings.action_retry)) }
            }) {
                Text(stringResource(CommonStrings.screen_threads_inbox_mark_failed))
            }
        }
        when {
            state.rows.isFailure() -> Text(stringResource(CommonStrings.screen_threads_inbox_load_failed))
            state.rows.dataOrNull() == null -> CircularProgressIndicator()
            state.rows.dataOrNull().isNullOrEmpty() -> {
                Text(stringResource(if (state.filter ==
                    ThreadsInboxFilter.Unread) {
                        CommonStrings.screen_threads_inbox_caught_up_title
                    } else {
                        CommonStrings.screen_threads_inbox_empty
                    }))
                TextButton(onClick = { state.eventSink(ThreadsInboxEvent.SelectFilter(ThreadsInboxFilter.All)) }) {
                    Text(stringResource(CommonStrings.screen_threads_inbox_show_all))
                }
            }
            else -> LazyColumn {
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
                        backgroundContent = { Text(stringResource(CommonStrings.screen_threads_inbox_mark_read)) },
                    ) {
                    ListItem(
                        headlineContent = { Text(thread.roomName ?: thread.key.roomId.value) },
                        supportingContent = {
                            Column {
                                Text((thread.rootEvent.content as? MessageContent)?.body.orEmpty(), maxLines = 1)
                                Text((thread.latestEvent?.content as? MessageContent)?.body.orEmpty(), maxLines = 1)
                            }
                        },
                        trailingContent = { if (thread.isUnread) Text(thread.numUnread.toString()) },
                        modifier = Modifier.alpha(if (thread.isUnread && thread.key !in state.visited) 1f else 0.55f)
                            .combinedClickable(onClick = { onOpen(thread.key) }, onLongClick = { menu = thread }),
                    )
                    }
                }
                item {
                    TextButton(onClick = { state.eventSink(ThreadsInboxEvent.LoadMore) }) { Text(stringResource(CommonStrings.action_load_more)) }
                }
                item { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 96.dp)) }
            }
        }
    }
    menu?.let { thread ->
        ModalBottomSheet(onDismissRequest = { menu = null }) {
            TextButton(onClick = {
                state.eventSink(ThreadsInboxEvent.MarkAsRead(thread.key))
                menu = null
            }) {
                Text(stringResource(CommonStrings.screen_threads_inbox_mark_read))
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
