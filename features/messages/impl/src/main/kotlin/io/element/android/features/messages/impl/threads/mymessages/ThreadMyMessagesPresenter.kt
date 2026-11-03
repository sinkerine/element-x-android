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
import androidx.compose.runtime.produceState
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
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.PollContent
import io.element.android.libraries.matrix.api.timeline.item.event.getDisambiguatedDisplayName
import io.element.android.libraries.matrix.ui.model.getAvatarData
import io.element.android.libraries.ui.strings.CommonStrings
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
        val events by timeline.timelineItems.collectAsState(initial = emptyList())
        val mapped by produceState<Pair<List<MatrixTimelineItem>?, ImmutableList<ThreadMyMessageItem>>>(null to persistentListOf(), events) {
            value = withContext(dispatchers.computation) {
                val chronologicalEvents = events.filterIsInstance<MatrixTimelineItem.Event>()
                val predecessors = chronologicalEvents.zipWithNext().associate { (before, after) -> after.eventId to before.event }
                events to events.ownThreadMessages().asReversed().map { (_, event) ->
                    val body = summaryFormatter.format(event).toString()
                    val predecessor = predecessors[event.eventId]
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
                        context = predecessor?.let {
                            ThreadMyMessageContext(
                                if (it.isOwn) context.getString(CommonStrings.common_you) else it.senderProfile.getDisambiguatedDisplayName(it.sender),
                                summaryFormatter.format(it).toString().lineSequence().first().take(80),
                            )
                        },
                    )
                }.toImmutableList()
            }
        }
        var visibleIds by remember { mutableStateOf(persistentListOf<EventId>()) }
        var hasFreshVisibility by remember { mutableStateOf(false) }
        val isMappingFresh = mapped.first == events
        val items = mapped.second.map { it.copy(isOnScreen = it.eventId in visibleIds) }.toImmutableList()
        var isOpen by remember { mutableStateOf(false) }
        var reachedStart by remember { mutableStateOf(false) }
        val isComplete = reachedStart || !pagination.hasMoreToLoad
        var loadState by remember { mutableStateOf(ThreadMyMessagesLoadState.Idle) }
        val isLimitReached = !isComplete && loadedPages >= MY_MESSAGES_MAX_PAGES
        var retry by remember { mutableIntStateOf(0) }
        var currentEventId by remember { mutableStateOf<EventId?>(null) }
        var pendingJump by remember { mutableStateOf<EventId?>(null) }
        var announcement by remember { mutableStateOf<String?>(null) }

        // Keep stepping from a dispatched jump until its first visible-range confirmation arrives.
        var awaitingVisibility by remember { mutableStateOf(false) }
        var pendingPreviousAnchor by remember { mutableStateOf<EventId?>(null) }
        var peekId by remember { mutableStateOf<EventId?>(null) }
        val positions = events.mapIndexedNotNull { index, item -> (item as? MatrixTimelineItem.Event)?.eventId?.let { it to index } }.toMap()
        val anchor = currentEventId?.takeIf { it in visibleIds || awaitingVisibility } ?: visibleIds.lastOrNull()
        fun previous(anchorId: EventId?): EventId? = positions[anchorId]?.let { position ->
            items.firstOrNull { (positions[it.eventId] ?: Int.MAX_VALUE) < position }?.eventId
        }
        val previousEventId = previous(anchor)
        fun jump(eventId: EventId) {
            scope.launch {
                val item = items.firstOrNull { it.eventId == eventId }
                if (item != null && timeline.isEventLoaded(eventId)) {
                    currentEventId = eventId
                    awaitingVisibility = true
                    pendingJump = eventId
                    val resource = if (item.isRoot) R.string.a11y_thread_my_messages_jumped_root else R.string.a11y_thread_my_messages_jumped
                    announcement = context.getString(resource, item.time, item.snippet)
                } else {
                    snackbarDispatcher.post(SnackbarMessage(R.string.screen_thread_my_messages_jump_failed))
                }
            }
        }
        LaunchedEffect(pendingPreviousAnchor, events, mapped, isComplete, isLimitReached, loadState) {
            val pendingAnchor = pendingPreviousAnchor ?: return@LaunchedEffect
            if (!isMappingFresh) return@LaunchedEffect
            val target = previous(pendingAnchor)
            if (target != null) {
                pendingPreviousAnchor = null
                jump(target)
            } else if (isComplete || isLimitReached || loadState == ThreadMyMessagesLoadState.Failed) {
                pendingPreviousAnchor = null
                if (isComplete) snackbarDispatcher.post(SnackbarMessage(R.string.screen_thread_my_messages_no_earlier))
            }
        }

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
                    hasFreshVisibility = false
                    visibleIds = persistentListOf()
                    awaitingVisibility = false
                    isOpen = true
                    val count = if (isComplete) items.size.toString() else context.getString(R.string.screen_thread_my_messages_count_more, items.size)
                    announcement = context.getString(R.string.a11y_thread_my_messages_opened, count)
                }
                ThreadMyMessagesEvent.Close -> {
                    hasFreshVisibility = false
                    visibleIds = persistentListOf()
                    announcement = null
                    peekId = null
                    pendingPreviousAnchor = null
                    awaitingVisibility = false
                    isOpen = false
                }
                ThreadMyMessagesEvent.Retry -> if (isOpen && loadState == ThreadMyMessagesLoadState.Failed) retry++
                is ThreadMyMessagesEvent.JumpTo -> {
                    pendingPreviousAnchor = null
                    jump(event.eventId)
                }
                ThreadMyMessagesEvent.JumpToPrevious -> {
                    pendingPreviousAnchor = null
                    if (!isMappingFresh) {
                        pendingPreviousAnchor = anchor
                    } else if (previousEventId != null) {
                        jump(previousEventId)
                    } else if (isComplete) {
                        scope.launch { snackbarDispatcher.post(SnackbarMessage(R.string.screen_thread_my_messages_no_earlier)) }
                    } else if (!isLimitReached && loadState != ThreadMyMessagesLoadState.Failed) {
                        pendingPreviousAnchor = anchor
                    }
                }
                is ThreadMyMessagesEvent.VisibleEventsChanged -> {
                    visibleIds = event.newestFirst.toPersistentList()
                    hasFreshVisibility = true
                    awaitingVisibility = false
                }
                is ThreadMyMessagesEvent.Peek -> peekId = event.eventId
                ThreadMyMessagesEvent.EndPeek -> peekId = null
                is ThreadMyMessagesEvent.Preview -> items.firstOrNull { it.eventId == event.eventId }?.let {
                    announcement = listOfNotNull(
                        "${context.getString(CommonStrings.common_you)} · ${it.fullTime}",
                        context.getString(CommonStrings.common_edited_suffix).takeIf { _ -> it.isEdited },
                        context.getString(R.string.screen_thread_my_messages_thread_root).takeIf { _ -> it.isRoot },
                        it.body,
                        it.context?.let { context.getString(R.string.screen_thread_my_messages_context, it.senderName, it.snippet) },
                    ).joinToString(", ")
                }
                ThreadMyMessagesEvent.JumpConsumed -> pendingJump = null
                ThreadMyMessagesEvent.AnnouncementConsumed -> announcement = null
            }
        }

        return ThreadMyMessagesState(
            isEntryVisible = openedFromThreadsInbox || items.isNotEmpty() || isOpen,
            ownAvatar = profile.getAvatarData(AvatarSize.ThreadMyMessagesButton),
            isOpen = isOpen,
            items = items,
            isComplete = isComplete,
            isLimitReached = isLimitReached,
            loadState = loadState,
            currentEventId = currentEventId,
            pendingJump = pendingJump,
            announcement = announcement,
            previousEventId = previousEventId,
            isReadingPositionReady = hasFreshVisibility && isMappingFresh,
            peek = peekId?.takeIf { id -> isOpen && items.any { it.eventId == id } }?.let {
                if (it in visibleIds) ThreadMyMessagePeek.Outline(it) else ThreadMyMessagePeek.Card(it)
            },
            eventSink = ::handleEvent,
        )
    }
}
