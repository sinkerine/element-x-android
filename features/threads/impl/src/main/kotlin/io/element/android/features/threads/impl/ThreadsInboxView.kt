/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.threads.impl

import androidx.compose.foundation.background
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    val mineDescription = stringResource(CommonStrings.screen_threads_inbox_tab_mine) + ", " +
        pluralStringResource(io.element.android.libraries.ui.strings.R.plurals.a11y_threads_inbox_unread, state.mineUnreadCount, state.mineUnreadCount)
    val otherDescription = stringResource(CommonStrings.screen_threads_inbox_tab_other) + ", " +
        pluralStringResource(io.element.android.libraries.ui.strings.R.plurals.a11y_threads_inbox_unread, state.otherUnreadCount, state.otherUnreadCount)
    LaunchedEffect(listState, state.tab, state.filter) {
        snapshotFlow {
            val layout = listState.layoutInfo
            layout.totalItemsCount > 2 && (layout.visibleItemsInfo.lastOrNull()?.index ?: 0) >= layout.totalItemsCount - 3
        }.distinctUntilChanged().collect { nearEnd -> if (nearEnd) state.eventSink(ThreadsInboxEvent.LoadMore) }
    }
    Column(modifier.padding(16.dp)) {
        TextButton(onClick = { state.eventSink(ThreadsInboxEvent.MarkAllAsRead) }) {
            Box(Modifier.padding(end = 8.dp)) {
                Icon(CompoundIcons.Check(), null)
                Icon(CompoundIcons.Check(), null, modifier = Modifier.padding(start = 8.dp))
            }
            Text(stringResource(CommonStrings.screen_threads_inbox_mark_all_read))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.tab == ThreadsInboxTab.Mine,
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = mineDescription },
                onClick = { state.eventSink(ThreadsInboxEvent.SelectTab(ThreadsInboxTab.Mine)) },
                label = { Text(stringResource(CommonStrings.screen_threads_inbox_tab_mine)) },
                trailingIcon = { io.element.android.libraries.designsystem.atomic.atoms.CounterAtom(state.mineUnreadCount) },
            )
            FilterChip(
                selected = state.tab == ThreadsInboxTab.Other,
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = otherDescription },
                onClick = { state.eventSink(ThreadsInboxEvent.SelectTab(ThreadsInboxTab.Other)) },
                label = { Text(stringResource(CommonStrings.screen_threads_inbox_tab_other)) },
                trailingIcon = { io.element.android.libraries.designsystem.atomic.atoms.CounterAtom(state.otherUnreadCount) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        }
        state.latestUnread?.let { thread ->
            Card(
                onClick = { onOpen(thread.key) },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = ElementTheme.colors.bgAccentRest, contentColor = ElementTheme.colors.textOnSolidPrimary),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row {
                        Icon(CompoundIcons.ArrowRight(), null)
                        Text(stringResource(CommonStrings.screen_threads_inbox_open_latest_unread))
                    }
                    Text(
                        thread.roomName.orEmpty() + " · " + state.summaries[thread.key]?.second.orEmpty(),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text((state.mineUnreadCount + state.otherUnreadCount).toString())
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
            state.rows.dataOrNull() == null -> Column {
                repeat(3) {
                    io.element.android.libraries.designsystem.atomic.molecules.IconTitlePlaceholdersRowMolecule(
                        iconSize = 52.dp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    )
                }
            }
            state.rows.dataOrNull().isNullOrEmpty() -> {
                Text(stringResource(if (state.filter ==
                    ThreadsInboxFilter.Unread) {
                        CommonStrings.screen_threads_inbox_caught_up_title
                    } else {
                        CommonStrings.screen_threads_inbox_empty
                    }))
                Icon(CompoundIcons.CheckCircle(), null)
                if (state.filter == ThreadsInboxFilter.Unread) {
                    Text(stringResource(CommonStrings.screen_threads_inbox_no_replies))
                } else {
                    Text(stringResource(CommonStrings.screen_threads_inbox_start_thread_hint))
                }
                TextButton(onClick = { state.eventSink(ThreadsInboxEvent.SelectFilter(ThreadsInboxFilter.All)) }) {
                    Text(stringResource(CommonStrings.screen_threads_inbox_show_all))
                }
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
                            Box(Modifier.fillMaxSize().background(ElementTheme.colors.bgAccentRest).padding(16.dp), contentAlignment = Alignment.CenterEnd) {
                                Text(stringResource(CommonStrings.screen_threads_inbox_mark_read), color = ElementTheme.colors.textOnSolidPrimary)
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
