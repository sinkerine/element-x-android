/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.threads.mymessages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.element.android.features.messages.impl.R
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.dateformatter.api.DateFormatter
import io.element.android.libraries.dateformatter.api.DateFormatterMode
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarDispatcher
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarMessage
import io.element.android.libraries.eventformatter.api.PinnedMessagesBannerFormatter
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.core.asEventId
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.PollContent
import io.element.android.libraries.matrix.ui.model.getAvatarData
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

@AssistedInject
class ThreadMyMessagesPresenter(
    @Assisted private val timeline: Timeline,
    @Assisted private val threadRootId: ThreadId,
    @Assisted private val openedFromThreadsInbox: Boolean,
    private val matrixClient: MatrixClient,
    private val summaryFormatter: PinnedMessagesBannerFormatter,
    private val dateFormatter: DateFormatter,
    private val snackbarDispatcher: SnackbarDispatcher,
    private val dispatchers: CoroutineDispatchers,
) : Presenter<ThreadMyMessagesState> {
    private var loadedPages by mutableIntStateOf(0)
    private val loadingMutex = Mutex()

    @AssistedFactory
    fun interface Factory {
        fun create(timeline: Timeline, threadRootId: ThreadId, openedFromThreadsInbox: Boolean): ThreadMyMessagesPresenter
    }

    @Composable
    override fun present(): ThreadMyMessagesState {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val profile by matrixClient.userProfile.collectAsState()
        val pagination by timeline.backwardPaginationStatus.collectAsState()
        val items by remember(timeline) {
            timeline.timelineItems.map { events ->
                events.ownThreadMessages().asReversed().map { (_, event) ->
                    val body = summaryFormatter.format(event).toString()
                    ThreadMyMessageItem(
                        eventId = requireNotNull(event.eventId),
                        time = dateFormatter.format(event.timestamp, DateFormatterMode.TimeOrDate, useRelative = true),
                        fullTime = dateFormatter.format(event.timestamp, DateFormatterMode.Full, useRelative = true),
                        snippet = body.lineSequence().first(),
                        body = body,
                        isRoot = event.eventId == threadRootId.asEventId(),
                        isEdited = when (val content = event.content) {
                            is MessageContent -> content.isEdited
                            is PollContent -> content.isEdited
                            else -> false
                        },
                    )
                }.toImmutableList()
            }.flowOn(dispatchers.computation)
        }.collectAsState(initial = persistentListOf())
        var isOpen by remember { mutableStateOf(false) }
        var reachedStart by remember { mutableStateOf(false) }
        val isComplete = reachedStart || !pagination.hasMoreToLoad
        var loadState by remember { mutableStateOf(ThreadMyMessagesLoadState.Idle) }
        val isLimitReached = !isComplete && loadedPages >= MY_MESSAGES_MAX_PAGES
        var retry by remember { mutableIntStateOf(0) }
        var currentEventId by remember { mutableStateOf<EventId?>(null) }
        var pendingJump by remember { mutableStateOf<EventId?>(null) }
        var announcement by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(isOpen, retry) {
            if (isOpen && !isComplete && !isLimitReached) {
                // A cancelled SDK page may still be finishing when the panel reopens.
                loadingMutex.withLock {
                    loadState = ThreadMyMessagesLoadState.Loading
                    try {
                        loadWholeThread(timeline, MY_MESSAGES_MAX_PAGES - loadedPages) { loadedPages++ }.fold(
                            onSuccess = {
                                reachedStart = it
                                loadState = ThreadMyMessagesLoadState.Idle
                            },
                            onFailure = {
                                Timber.w("Could not load older own thread messages")
                                loadState = ThreadMyMessagesLoadState.Failed
                            },
                        )
                    } finally {
                        if (loadState == ThreadMyMessagesLoadState.Loading) loadState = ThreadMyMessagesLoadState.Idle
                    }
                }
            }
        }

        fun handleEvent(event: ThreadMyMessagesEvent) {
            when (event) {
                ThreadMyMessagesEvent.Open -> if (!isOpen) {
                    isOpen = true
                    val count = if (isComplete) items.size.toString() else context.getString(R.string.screen_thread_my_messages_count_more, items.size)
                    announcement = context.getString(R.string.a11y_thread_my_messages_opened, count)
                }
                ThreadMyMessagesEvent.Close -> {
                    isOpen = false
                    announcement = null
                }
                ThreadMyMessagesEvent.Retry -> if (isOpen && loadState == ThreadMyMessagesLoadState.Failed) retry++
                is ThreadMyMessagesEvent.JumpTo -> scope.launch {
                    val item = items.firstOrNull { it.eventId == event.eventId }
                    if (item != null && timeline.isEventLoaded(event.eventId)) {
                        currentEventId = event.eventId
                        pendingJump = event.eventId
                        val resource = if (item.isRoot) R.string.a11y_thread_my_messages_jumped_root else R.string.a11y_thread_my_messages_jumped
                        announcement = context.getString(resource, item.time, item.snippet)
                    } else {
                        snackbarDispatcher.post(SnackbarMessage(R.string.screen_thread_my_messages_jump_failed))
                    }
                }
                ThreadMyMessagesEvent.JumpConsumed -> pendingJump = null
                ThreadMyMessagesEvent.AnnouncementConsumed -> announcement = null
            }
        }

        return ThreadMyMessagesState(
            isEntryVisible = openedFromThreadsInbox || items.isNotEmpty() || isOpen,
            ownAvatar = profile.getAvatarData(AvatarSize.CurrentUserTopBar),
            isOpen = isOpen,
            items = items,
            isComplete = isComplete,
            isLimitReached = isLimitReached,
            loadState = loadState,
            currentEventId = currentEventId,
            pendingJump = pendingJump,
            announcement = announcement,
            eventSink = ::handleEvent,
        )
    }
}
