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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
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
    private val unreadStore: InboxThreadUnreadStore,
) : CrossRoomThreadsService {
    override val threads = MutableStateFlow<AsyncData<ImmutableList<InboxThread>>>(AsyncData.Uninitialized)
    private var visit: Job? = null
    private val roomJobs = mutableMapOf<io.element.android.libraries.matrix.api.core.RoomId, Job>()
    private val rooms = mutableMapOf<io.element.android.libraries.matrix.api.core.RoomId, JoinedRoom>()
    private val rows = mutableMapOf<io.element.android.libraries.matrix.api.core.RoomId, List<InboxThread>>()
    private val receipts = Semaphore(4)
    private val recentRooms = mutableListOf<io.element.android.libraries.matrix.api.core.RoomId>()
    private val roomMetadata = mutableMapOf<io.element.android.libraries.matrix.api.core.RoomId, io.element.android.libraries.matrix.api.room.RoomInfo>()
    private var latestSummaries: List<io.element.android.libraries.matrix.api.roomlist.RoomSummary> = emptyList()
    private val discoveryOffset = kotlinx.coroutines.flow.MutableStateFlow(0)

    init {
        // With the visit closed retain only room-list observation and cached receipt refreshes.
        client.sessionCoroutineScope.launch {
            client.roomListService.allRooms.summaries.collect { summaries ->
                if (visit?.isActive == true || rows.isEmpty()) return@collect
                val joined = summaries.filter { it.info.currentUserMembership == CurrentUserMembership.JOINED }.map { it.roomId }.toSet()
                rows.keys.retainAll(joined)
                summaries.forEach { summary ->
                    val cached = rows[summary.roomId] ?: return@forEach
                    val inner = (client as RustMatrixClient).innerClient.getRoom(summary.roomId.value) ?: return@forEach
                    try {
                        rows[summary.roomId] = cached.map { item ->
                            val receipt = inner.loadUserReceipt(receiptType(), ReceiptThread.Thread(item.key.threadId.value), client.sessionId.value)
                            val read = receipt?.eventId == item.latestEvent?.eventId?.value
                            item.copy(
                                roomName = summary.info.name,
                                roomAvatarUrl = summary.info.avatarUrl,
                                roomNotificationMode = summary.info.userDefinedNotificationMode ?: RoomNotificationMode.ALL_MESSAGES,
                                numUnread = if (read) 0 else item.numUnread,
                                numUnreadMentions = if (read) 0 else item.numUnreadMentions,
                                readReceiptEventId = receipt?.eventId?.let(::EventId),
                            )
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        timber.log.Timber.w("Could not refresh cached thread receipts for room %s", summary.roomId)
                    } finally {
                        inner.close()
                    }
                }
                publish()
            }
        }
    }

    override fun startVisit() {
        if (visit?.isActive == true) return
        visit = client.sessionCoroutineScope.launch {
            client.roomListService.state.first { it == io.element.android.libraries.matrix.api.roomlist.RoomListService.State.Running }
            combine(client.roomListService.allRooms.summaries, discoveryOffset) { summaries, offset -> summaries to offset }
                .catch { error -> threads.value = AsyncData.Failure(error, threads.value.dataOrNull()) }
                .collect { (summaries, offset) ->
                latestSummaries = summaries
                coroutineScope {
                    val candidates = if (offset == 0) {
                        selectInboxRooms(summaries, rows.filterValues { it.isNotEmpty() }.keys, recentRooms)
                    } else {
                        summaries.filter { it.info.currentUserMembership == CurrentUserMembership.JOINED && !it.info.isSpace }
                        .sortedByDescending { it.latestEventTimestamp }.drop(offset).take(20)
                    }
                    val joined = summaries.filter { it.info.currentUserMembership == CurrentUserMembership.JOINED }.map { it.roomId }.toSet()
                    rows.keys.retainAll(joined)
                    summaries.forEach { roomMetadata[it.roomId] = it.info }
                    val ids = candidates.map { it.roomId }.toSet()
                    roomJobs.keys.filter { it !in ids }.forEach { id -> roomJobs.remove(id)?.cancelAndJoin() }
                    rows.replaceAll { id, items ->
                        val info = roomMetadata[id]
                        items.map { item ->
                            item.copy(
                            roomName = info?.name ?: item.roomName,
                            roomAvatarUrl = info?.avatarUrl,
                            roomNotificationMode = info?.userDefinedNotificationMode ?: RoomNotificationMode.ALL_MESSAGES,
                        )
                        }
                    }
                    if (rows.isNotEmpty() || candidates.isEmpty()) {
                        publish()
                    } else {
                        threads.value = AsyncData.Loading()
                    }
                    candidates.forEach { summary ->
                        if (roomJobs[summary.roomId]?.isActive == true) return@forEach
                        roomJobs[summary.roomId] = client.sessionCoroutineScope.launch {
                            val room = client.getJoinedRoom(summary.roomId) ?: return@launch
                            rooms[summary.roomId] = room
                            try {
                                val service = room.threadsListService
                                service.paginate().getOrThrow()
                                combine(
                                    service.subscribeToItemUpdates(),
                                    room.roomInfoFlow,
                                    client.notificationSettingsService.notificationSettingsChangeFlow.onStart { emit(Unit) },
                                ) { items, info, _ -> items to info }
                                    .distinctUntilChanged()
                                    .conflate()
                                    .collect { (items, info) ->
                                    val inner = (client as RustMatrixClient).innerClient.getRoom(summary.roomId.value)
                                        ?: return@collect
                                    try {
                                        val markedUnread = unreadStore.read()
                                        val notificationMode = client.notificationSettingsService.getRoomNotificationSettings(
                                            info.id,
                                            info.isEncrypted == true,
                                            info.isDirect,
                                        ).getOrThrow().mode
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
                                                // A stale cached classification must not be presented as exact current unread data.
                                                if (previous == null || previous.latestEvent?.eventId != item.latestEvent?.eventId ||
                                                    previous.readReceiptEventId?.value != receipt?.eventId
                                                ) {
                                                    throw error
                                                }
                                                timber.log.Timber.w(error, "Using unchanged cached inbox history for thread %s", item.threadId)
                                                InboxThreadHistory(previous.numUnread, previous.numUnreadMentions, previous.isParticipating, null)
                                            }
                                            InboxThread(
                                                key = InboxThreadKey(summary.roomId, item.threadId),
                                                roomName = info.name,
                                                roomAvatarUrl = info.avatarUrl,
                                                rootEvent = item.rootEvent,
                                                latestEvent = item.latestEvent,
                                                numberOfReplies = item.numberOfReplies,
                                                numUnread = history.unreadCount,
                                                numUnreadMentions = history.mentionCount,
                                                isParticipating = item.rootEvent.isOwn || history.participating,
                                                roomNotificationMode = notificationMode,
                                                readReceiptEventId = receipt?.eventId?.let(::EventId),
                                                isMarkedUnread = InboxThreadKey(summary.roomId, item.threadId) in markedUnread,
                                            )
                                        }
                                        publish()
                                    } finally {
                                        inner.close()
                                    }
                                }
                            } catch (error: Exception) {
                                if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                                timber.log.Timber.w(error, "Failed to load inbox thread history for room %s", summary.roomId)
                                threads.value = AsyncData.Failure(error, threads.value.dataOrNull())
                            } finally {
                                if (rooms[summary.roomId] === room) rooms.remove(summary.roomId)
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
        room.createTimeline(CreateTimelineParams.Threaded(io.element.android.libraries.matrix.api.core.ThreadId(rootId))).getOrThrow().use { timeline ->
            loadInboxThreadHistory(timeline, rootId, receiptId, client.sessionId)
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
        val count = latestSummaries.count { it.info.currentUserMembership == CurrentUserMembership.JOINED && !it.info.isSpace }
        if (discoveryOffset.value + 20 < count) discoveryOffset.value += 20
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
                            unreadStore.set(key, false).getOrThrow()
                            rows[key.roomId] = rows[key.roomId].orEmpty().map {
                                if (it.key == key && it.latestEvent?.eventId == snapshot.latestEvent?.eventId) {
                                    it.copy(numUnread = 0, numUnreadMentions = 0, readReceiptEventId = latest.eventId, isMarkedUnread = false)
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

    override suspend fun markAsUnread(key: InboxThreadKey): Result<Unit> = unreadStore.set(key, true).onSuccess {
        rows[key.roomId] = rows[key.roomId].orEmpty().map { if (it.key == key) it.copy(isMarkedUnread = true) else it }
        publish()
    }

    override suspend fun firstUnreadEventId(key: InboxThreadKey): Result<EventId?> = runCatchingExceptions {
        val item = threads.value.dataOrNull()?.find { it.key == key } ?: error("Thread is no longer available")
        if (!item.isUnread) return@runCatchingExceptions null
        recentRooms.remove(key.roomId)
        recentRooms.add(0, key.roomId)
        val room = client.getJoinedRoom(key.roomId) ?: error("Room is no longer joined")
        try {
            loadHistory(room, key.threadId.value, item.readReceiptEventId).firstUnread
        } finally {
            room.close()
        }
    }
}
