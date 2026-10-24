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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
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
    private val rooms = mutableMapOf<io.element.android.libraries.matrix.api.core.RoomId, JoinedRoom>()
    private val rows = mutableMapOf<io.element.android.libraries.matrix.api.core.RoomId, List<InboxThread>>()
    private val receipts = Semaphore(4)

    override fun startVisit() {
        if (visit?.isActive == true) return
        visit = client.sessionCoroutineScope.launch {
            client.roomListService.allRooms.summaries.catch { error ->
                threads.value = AsyncData.Failure(error, threads.value.dataOrNull())
            }.collectLatest { summaries ->
                if (!client.roomListService.isInitialSyncDone) return@collectLatest
                coroutineScope {
                    val candidates = summaries.filter { it.info.currentUserMembership == CurrentUserMembership.JOINED }
                        .sortedByDescending { it.latestEventTimestamp }.take(20)
                    rows.keys.retainAll(candidates.map { it.roomId }.toSet())
                    publish()
                    candidates.forEach { summary ->
                        launch {
                            val room = client.getJoinedRoom(summary.roomId) ?: return@launch
                            rooms[summary.roomId] = room
                            try {
                                val service = room.threadsListService
                                service.paginate().getOrThrow()
                                service.subscribeToItemUpdates().collectLatest { items ->
                                    val inner = (client as RustMatrixClient).innerClient.getRoom(summary.roomId.value)
                                        ?: return@collectLatest
                                    try {
                                        rows[summary.roomId] = items.map { item ->
                                            val receipt = inner.loadUserReceipt(
                                                receiptType(),
                                                ReceiptThread.Thread(item.threadId.value),
                                                client.sessionId.value
                                            )
                                            InboxThread(
                                                key = InboxThreadKey(summary.roomId, item.threadId),
                                                roomName = summary.info.name,
                                                roomAvatarUrl = summary.info.avatarUrl,
                                                rootEvent = item.rootEvent,
                                                latestEvent = item.latestEvent,
                                                numberOfReplies = item.numberOfReplies,
                                                numUnread = if (item.latestEvent != null && item.latestEvent?.eventId?.value != receipt?.eventId &&
                                                    item.latestEvent?.isOwn == false
                                                ) {
                                                    1
                                                } else {
                                                    0
                                                },
                                                numUnreadMentions = 0,
                                                isParticipating = item.rootEvent.isOwn || item.latestEvent?.isOwn == true,
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

    private fun publish() {
        threads.value = AsyncData.Success(rows.values.flatten().sortedByDescending { it.timestamp }.toImmutableList())
    }

    override fun endVisit() {
        val previous = visit
        visit = null
        previous?.cancel()
        client.sessionCoroutineScope.launch { previous?.cancelAndJoin() }
    }

    override suspend fun paginate(): Result<Unit> = runCatchingExceptions {
        rooms.values.toList().forEach { it.threadsListService.paginate().getOrThrow() }
    }

    private suspend fun receiptType() = if (preferences.isSendPublicReadReceiptsEnabled().first()) ReceiptType.READ else ReceiptType.READ_PRIVATE

    override suspend fun markAsRead(threads: List<InboxThreadKey>): Result<Unit> = runCatchingExceptions {
        coroutineScope {
            threads.forEach { key ->
                launch {
                    receipts.withPermit {
                        val latest = this@KotlinCrossRoomThreadsService.threads.value.dataOrNull()?.find { it.key == key }?.latestEvent
                            ?: error("Thread is no longer available")
                        val inner = (client as RustMatrixClient).innerClient.getRoom(key.roomId.value) ?: error("Room is no longer joined")
                        try {
                            inner.sendSingleReceipt(receiptType(), ReceiptThread.Thread(key.threadId.value), latest.eventId.value)
                            rows[key.roomId] = rows[key.roomId].orEmpty().map {
                                if (it.key == key) it.copy(numUnread = 0, readReceiptEventId = latest.eventId) else it
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
            room.createTimeline(CreateTimelineParams.Threaded(key.threadId)).getOrThrow().use { timeline ->
                var events = timeline.timelineItems.first().filterIsInstance<MatrixTimelineItem.Event>().map { it.event }
                while (item.readReceiptEventId != null && events.none { it.eventId == item.readReceiptEventId } &&
                    timeline.backwardPaginationStatus.value.hasMoreToLoad
                ) {
                    timeline.paginate(Timeline.PaginationDirection.BACKWARDS).getOrThrow()
                    events = timeline.timelineItems.first().filterIsInstance<MatrixTimelineItem.Event>().map { it.event }
                }
                val ordered = events.sortedBy { it.timestamp }
                val receiptIndex = ordered.indexOfFirst { it.eventId == item.readReceiptEventId }
                if (item.readReceiptEventId != null && receiptIndex < 0) error("Thread receipt could not be loaded")
                ordered.drop(receiptIndex + 1).firstOrNull { it.eventId?.value != key.threadId.value }?.eventId
            }
        } finally {
            room.close()
        }
    }
}
