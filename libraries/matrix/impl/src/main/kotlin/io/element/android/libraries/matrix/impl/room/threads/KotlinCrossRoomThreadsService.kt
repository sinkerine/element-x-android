/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.room.threads

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.room.CreateTimelineParams
import io.element.android.libraries.matrix.api.room.CurrentUserMembership
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.RoomNotificationMode
import io.element.android.libraries.matrix.api.room.threads.CrossRoomThreadsService
import io.element.android.libraries.matrix.api.room.threads.InboxThread
import io.element.android.libraries.matrix.api.room.threads.InboxThreadKey
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.impl.RustMatrixClient
import io.element.android.libraries.preferences.api.store.SessionPreferencesStore
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.matrix.rustcomponents.sdk.ReceiptThread
import org.matrix.rustcomponents.sdk.ReceiptType

/** Flagged prototype: bounded subscriptions, exact receipt IDs, approximate counts and participation. */
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class)
@Inject
class KotlinCrossRoomThreadsService(
    private val client: MatrixClient,
    private val preferences: SessionPreferencesStore,
) : CrossRoomThreadsService {
    override val threads = MutableStateFlow<AsyncData<ImmutableList<InboxThread>>>(AsyncData.Uninitialized)
    private var visit: Job? = null
    private val roomJobs = mutableMapOf<io.element.android.libraries.matrix.api.core.RoomId, Job>()
    private val rooms = mutableMapOf<io.element.android.libraries.matrix.api.core.RoomId, JoinedRoom>()
    private val rows = mutableMapOf<io.element.android.libraries.matrix.api.core.RoomId, List<InboxThread>>()
    private val receipts = Semaphore(4)

    override fun startVisit() {
        if (visit?.isActive == true) return
        visit = client.sessionCoroutineScope.launch {
            client.roomListService.state.first { it == io.element.android.libraries.matrix.api.roomlist.RoomListService.State.Running }
            client.roomListService.allRooms.summaries.catch { error ->
                threads.value = AsyncData.Failure(error, threads.value.dataOrNull())
            }.collect { summaries ->
                coroutineScope {
                    val candidates = summaries.filter { it.info.currentUserMembership == CurrentUserMembership.JOINED }
                        .sortedByDescending { it.latestEventTimestamp }.take(20)
                    val ids = candidates.map { it.roomId }.toSet()
                    roomJobs.keys.filter { it !in ids }.forEach { id -> roomJobs.remove(id)?.cancelAndJoin() }
                    rows.keys.retainAll(ids)
                    publish()
                    candidates.forEach { summary ->
                        if (roomJobs[summary.roomId]?.isActive == true) return@forEach
                        roomJobs[summary.roomId] = client.sessionCoroutineScope.launch {
                            val room = client.getJoinedRoom(summary.roomId) ?: return@launch
                            rooms[summary.roomId] = room
                            try {
                                val service = room.threadsListService
                                service.paginate().getOrThrow()
                                service.subscribeToItemUpdates().collectLatest { items ->
                                    val inner = (client as RustMatrixClient).innerClient.getRoom(summary.roomId.value)
                                        ?: return@collectLatest
                                    try {
                                        val previousRows = rows[summary.roomId].orEmpty()
                                        rows[summary.roomId] = items.map { item ->
                                            val receipt = inner.loadUserReceipt(
                                                receiptType(),
                                                ReceiptThread.Thread(item.threadId.value),
                                                client.sessionId.value
                                            )
                                            val previous = previousRows.find { it.key.threadId == item.threadId }
                                            val history = runCatchingExceptions {
                                                loadHistory(room, item.threadId.value, receipt?.eventId?.let(::EventId))
                                            }.getOrElse { error ->
                                                timber.log.Timber.w(error, "Using cached inbox history for thread %s", item.threadId)
                                                InboxThreadHistory(
                                                    unreadCount = if (receipt?.eventId == item.latestEvent?.eventId?.value) 0 else previous?.numUnread ?: 1,
                                                    mentionCount = previous?.numUnreadMentions ?: 0,
                                                    participating = previous?.isParticipating ?: item.rootEvent.isOwn,
                                                    firstUnread = null,
                                                )
                                            }
                                            InboxThread(
                                                key = InboxThreadKey(summary.roomId, item.threadId),
                                                roomName = summary.info.name,
                                                roomAvatarUrl = summary.info.avatarUrl,
                                                rootEvent = item.rootEvent,
                                                latestEvent = item.latestEvent,
                                                numberOfReplies = item.numberOfReplies,
                                                numUnread = history.unreadCount,
                                                numUnreadMentions = history.mentionCount,
                                                isParticipating = item.rootEvent.isOwn || history.participating,
                                                roomNotificationMode = summary.info.userDefinedNotificationMode ?: RoomNotificationMode.ALL_MESSAGES,
                                                readReceiptEventId = receipt?.eventId?.let(::EventId),
                                            )
                                        }
                                        publish()
                                    } finally {
                                        inner.close()
                                    }
                                }
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                timber.log.Timber.w(error, "Failed to load inbox thread history for room %s", summary.roomId)
                                threads.value = AsyncData.Failure(error, threads.value.dataOrNull())
                            } finally {
                                rooms.remove(summary.roomId)
                                room.threadsListService.destroy()
                                room.close()
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun loadHistory(room: JoinedRoom, rootId: String, receiptId: EventId?): InboxThreadHistory =
        kotlinx.coroutines.withTimeout(30_000) {
            coroutineScope {
            room.createTimeline(CreateTimelineParams.Threaded(io.element.android.libraries.matrix.api.core.ThreadId(rootId))).getOrThrow().use { timeline ->
                val subscription = kotlinx.coroutines.Job(coroutineContext[Job])
                val historyItems = timeline.timelineItems.stateIn(
                    kotlinx.coroutines.CoroutineScope(coroutineContext + subscription),
                    SharingStarted.Eagerly,
                    emptyList(),
                )
                try {
                timeline.paginate(Timeline.PaginationDirection.BACKWARDS).getOrThrow()
                var events = historyItems.first { it.any { item -> item is MatrixTimelineItem.Event } }
                    .filterIsInstance<MatrixTimelineItem.Event>().map { it.event }
                while (timeline.backwardPaginationStatus.value.hasMoreToLoad &&
                    events.none { it.eventId?.value == rootId }
                ) {
                    timeline.backwardPaginationStatus.first { !it.isPaginating }
                    if (!timeline.backwardPaginationStatus.value.hasMoreToLoad) break
                    val previous = events.map { it.eventId }
                    val reachedEnd = timeline.paginate(Timeline.PaginationDirection.BACKWARDS).getOrThrow()
                    events = historyItems.first { items ->
                        reachedEnd || items.filterIsInstance<MatrixTimelineItem.Event>().map { it.eventId } != previous
                    }.filterIsInstance<MatrixTimelineItem.Event>().map { it.event }
                    if (reachedEnd) break
                }
                aggregateThreadHistory(events, rootId, receiptId, client.sessionId)
                } finally {
                    subscription.cancel()
                }
            }
            }
        }

    private fun publish() {
        threads.value = AsyncData.Success(rows.values.flatten().sortedByDescending { it.timestamp }.toImmutableList())
    }

    override fun endVisit() {
        val previous = visit
        visit = null
        previous?.cancel()
        roomJobs.values.forEach { it.cancel() }
        roomJobs.clear()
        client.sessionCoroutineScope.launch { previous?.cancelAndJoin() }
    }

    override suspend fun paginate(): Result<Unit> = runCatchingExceptions {
        rooms.values.toList().forEach { it.threadsListService.paginate().getOrThrow() }
    }

    private suspend fun receiptType() = if (preferences.isSendPublicReadReceiptsEnabled().first()) ReceiptType.READ else ReceiptType.READ_PRIVATE

    override suspend fun markAsRead(threads: List<InboxThreadKey>): Result<Unit> = markSnapshotAsRead(
        threads.map { key -> this.threads.value.dataOrNull()?.find { it.key == key } ?: return Result.failure(IllegalStateException("Thread unavailable")) }
    )

    override suspend fun markSnapshotAsRead(threads: List<InboxThread>): Result<Unit> = runCatchingExceptions {
        coroutineScope {
            threads.forEach { snapshot ->
                val key = snapshot.key
                launch {
                    receipts.withPermit {
                        val latest = snapshot.latestEvent ?: snapshot.rootEvent
                        val inner = (client as RustMatrixClient).innerClient.getRoom(key.roomId.value) ?: error("Room is no longer joined")
                        try {
                            inner.sendSingleReceipt(receiptType(), ReceiptThread.Thread(key.threadId.value), latest.eventId.value)
                            rows[key.roomId] = rows[key.roomId].orEmpty().map {
                                if (it.key == key && it.latestEvent?.eventId == snapshot.latestEvent?.eventId) {
                                    it.copy(numUnread = 0, numUnreadMentions = 0, readReceiptEventId = latest.eventId)
                                } else {
                                    it
                                }
                            }
                            publish()
                        } finally {
                            inner.close()
                        }
                    }
                }
            }
        }
    }

    override suspend fun firstUnreadEventId(key: InboxThreadKey): Result<EventId?> = runCatchingExceptions {
        val item = threads.value.dataOrNull()?.find { it.key == key } ?: error("Thread is no longer available")
        if (!item.isUnread) return@runCatchingExceptions null
        val room = client.getJoinedRoom(key.roomId) ?: error("Room is no longer joined")
        try {
            loadHistory(room, key.threadId.value, item.readReceiptEventId).firstUnread
        } finally {
            room.close()
        }
    }
}
