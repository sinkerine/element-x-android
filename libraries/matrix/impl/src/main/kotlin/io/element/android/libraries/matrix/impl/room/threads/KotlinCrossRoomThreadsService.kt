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
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.room.CreateTimelineParams
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.threads.CrossRoomThreadsService
import io.element.android.libraries.matrix.api.room.threads.InboxThread
import io.element.android.libraries.matrix.api.room.threads.InboxThreadKey
import io.element.android.libraries.matrix.api.room.threads.ThreadListItem
import io.element.android.libraries.matrix.api.roomlist.RoomListService
import io.element.android.libraries.matrix.impl.RustMatrixClient
import io.element.android.libraries.matrix.impl.timeline.item.event.TimelineEventContentMapper
import io.element.android.libraries.preferences.api.store.SessionPreferencesStore
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.matrix.rustcomponents.sdk.ThreadInboxPage
import org.matrix.rustcomponents.sdk.ThreadInboxPageRoom

/** User-driven pages; no account-wide background badge or history scan. */
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class)
@Inject
class KotlinCrossRoomThreadsService(
    private val client: MatrixClient,
    private val preferences: SessionPreferencesStore,
    private val unreadStore: InboxThreadUnreadStore,
    private val featureFlags: FeatureFlagService,
    private val source: ThreadInboxSource,
    private val sentReceipts: InboxSentReceiptStore,
) : CrossRoomThreadsService {
    override val threads = MutableStateFlow<AsyncData<kotlinx.collections.immutable.ImmutableList<InboxThread>>>(AsyncData.Uninitialized)
    override val hasMore = MutableStateFlow(true)
    override val successfulTimelineReads get() = (client as? RustMatrixClient)?.threadReadActions?.successfulTimelineReads
        ?: kotlinx.coroutines.flow.emptyFlow()
    private val innerClient get() = (client as RustMatrixClient).innerClient
    private val rows = LinkedHashMap<InboxThreadKey, InboxThread>()
    private val refreshLock = Mutex()
    private val rowsLock = Mutex()
    private val receipts = Semaphore(4)
    private val receiptLoads = Semaphore(8)
    private val scans = Semaphore(8)
    private val contentMapper = TimelineEventContentMapper()
    private var visiting = false
    private var dirty = true
    private var loadJob: kotlinx.coroutines.Job? = null
    private var pendingPage: ThreadInboxPage? = null
    private var replacing = false
    private data class HistoryBoundary(val latest: EventId, val receipts: Set<EventId>, val history: InboxThreadHistory)
    private val historyCache = object : LinkedHashMap<InboxThreadKey, HistoryBoundary>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<InboxThreadKey, HistoryBoundary>?): Boolean = size > 500
    }

    init {
        client.sessionCoroutineScope.launch {
            source.updates.collect { dirty = true }
        }
    }

    override fun startVisit() {
        visiting = true
        if (loadJob?.isActive == true) return
        if (dirty || threads.value.isUninitialized() || threads.value.isFailure() || threads.value.isLoading()) {
            loadJob = client.sessionCoroutineScope.launch { loadPage(reset = dirty) }
        }
    }

    override fun endVisit() {
        visiting = false
        loadJob?.cancel()
    }

    override suspend fun paginate(): Result<Unit> {
        if (!visiting || threads.value.isLoading() || !hasMore.value) return Result.success(Unit)
        return loadPage(reset = false)
    }

    private suspend fun loadPage(reset: Boolean): Result<Unit> = refreshLock.withLock {
        runCatchingExceptions {
            if (!featureFlags.isFeatureEnabled(FeatureFlags.CrossRoomThreads) ||
                !featureFlags.isFeatureEnabled(FeatureFlags.Threads)) {
                    return@runCatchingExceptions
                }
            threads.value = AsyncData.Loading(threads.value.dataOrNull())
            client.roomListService.state.first { it == RoomListService.State.Running }
            val started = System.nanoTime()
            if (reset) {
                source.reset()
                pendingPage = null
                hasMore.value = true
                dirty = false
                replacing = true
            }
            val page = pendingPage ?: source.nextPage().also { pendingPage = it }
            val pageKeys = page.rooms.flatMap { room -> room.threads.map { InboxThreadKey(RoomId(room.roomId), ThreadId(it.item.rootEvent.eventId)) } }
            val stale = if (replacing) rowsLock.withLock { rows.keys - pageKeys.toSet() } else emptySet()
            val marked = unreadStore.read()
            // Rows are published independently, so one slow or failing thread never hides the others.
            // The first failure fails the page after the rest finish; the page is retained for retry.
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            coroutineScope {
                page.rooms.map { room -> async { mapRoom(room, marked, started, failure) } }.awaitAll()
            }
            failure.get()?.let { throw it }
            rowsLock.withLock {
                if (replacing) rows.keys.removeAll(stale)
                replacing = false
                hasMore.value = page.hasMore
                pendingPage = null
                publish(loading = false)
            }
            timber.log.Timber.d(
                "Inbox page loaded: threads=%d hasMore=%s elapsedMs=%d",
                rows.size,
                hasMore.value,
                (System.nanoTime() - started) / 1_000_000,
            )
        }.onFailure {
            threads.value = AsyncData.Failure(it, threads.value.dataOrNull())
            timber.log.Timber.w("Inbox page failed: %s", it::class.java.simpleName)
        }
    }

    private suspend fun mapRoom(
        snapshot: ThreadInboxPageRoom,
        marked: Set<InboxThreadKey>,
        started: Long,
        failure: java.util.concurrent.atomic.AtomicReference<Throwable?>,
    ) {
        val id = RoomId(snapshot.roomId)
        if (snapshot.threads.isEmpty()) return
        val (info, mode) = runCatchingExceptions {
            val room = client.getJoinedRoom(id) ?: error("Joined room unavailable during inbox discovery")
            try {
                val info = room.roomInfoFlow.value
                info to client.notificationSettingsService.getRoomNotificationSettings(id, info.isEncrypted == true, info.isDirect).getOrThrow().mode
            } finally {
                room.close()
            }
        }.getOrElse {
            failure.compareAndSet(null, it)
            return
        }
        coroutineScope {
            snapshot.threads.map { native ->
                async {
                    runCatchingExceptions { mapThread(id, native, info, mode, marked, started) }.onFailure {
                        timber.log.Timber.w("Inbox thread failed: %s in room %s", it::class.java.simpleName, id)
                        failure.compareAndSet(null, it)
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun mapThread(
        id: RoomId,
        native: org.matrix.rustcomponents.sdk.ThreadInboxPageThread,
        info: io.element.android.libraries.matrix.api.room.RoomInfo,
        mode: io.element.android.libraries.matrix.api.room.RoomNotificationMode,
        marked: Set<InboxThreadKey>,
        started: Long,
    ) {
        val item = native.item.map(contentMapper)
        val key = InboxThreadKey(id, item.threadId)
        val history = history(key, item, receiptLoads.withPermit { receipts(key) })
        val row = InboxThread(
            key = key,
            roomName = info.name,
            roomAvatarUrl = info.avatarUrl,
            rootEvent = item.rootEvent,
            latestEvent = item.latestEvent,
            numberOfReplies = item.numberOfReplies,
            numUnread = history.unreadCount,
            numUnreadMentions = history.mentionCount,
            // The server's bundled summary is authoritative for participation; no history is needed.
            isParticipating = item.rootEvent.isOwn || native.participated || history.participating,
            roomNotificationMode = mode,
            readReceiptEventId = history.effectiveReceipt,
            isMarkedUnread = key in marked,
        )
        rowsLock.withLock {
            rows[key] = row
            publish(loading = true)
        }
        timber.log.Timber.d("Inbox rows published: rows=%d elapsedMs=%d", rows.size, (System.nanoTime() - started) / 1_000_000)
    }

    private suspend fun receipts(key: InboxThreadKey): Set<EventId> =
        sentReceipts.merge(key, source.receipts(key.roomId, key.threadId))

    private suspend fun history(key: InboxThreadKey, item: ThreadListItem, receiptIds: Set<EventId>): InboxThreadHistory {
        val latest = item.latestEvent ?: item.rootEvent
        localThreadHistory(latest.eventId, latest.isOwn, item.latestEvent != null, receiptIds)?.let { return it }
        synchronized(historyCache) { historyCache[key] }?.takeIf { it.latest == latest.eventId && it.receipts == receiptIds }?.let { return it.history }
        return scans.withPermit { scan(key, receiptIds) }.also {
            synchronized(historyCache) { historyCache[key] = HistoryBoundary(latest.eventId, receiptIds, it) }
        }
    }

    private suspend fun scan(key: InboxThreadKey, receiptIds: Set<EventId>): InboxThreadHistory {
        // A timeout is a failure of this row, not a cancellation of the visit.
        val unread = kotlinx.coroutines.withTimeoutOrNull(20_000) { source.unread(key.roomId, key.threadId, receiptIds) }
            ?: error("Thread unread scan timed out")
        if (!unread.complete) {
            timber.log.Timber.w("Inbox unread scan capped for thread %s in room %s", key.threadId, key.roomId)
        }
        return InboxThreadHistory(
            unreadCount = unread.unread.toLong(),
            mentionCount = unread.mentions.toLong(),
            participating = unread.participated,
            firstUnread = unread.firstUnread?.let(::EventId),
            effectiveReceipt = unread.boundary?.let(::EventId),
            complete = unread.complete,
        )
    }

    private suspend fun loadHistory(room: JoinedRoom, rootId: String, receiptIds: Set<EventId>): InboxThreadHistory =
        try {
            kotlinx.coroutines.withTimeout(30_000) {
                room.createTimeline(CreateTimelineParams.Threaded(ThreadId(rootId))).getOrThrow().use { timeline ->
                    loadInboxThreadHistory(timeline, rootId, null, client.sessionId, receiptIds)
                }
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            timber.log.Timber.w("Inbox history timed out for thread %s in room %s", rootId, room.roomId)
            throw IllegalStateException("Thread history discovery timed out", error)
        }

    private fun publish(loading: Boolean) {
        val data = rows.values.sortedByDescending { it.timestamp }.toImmutableList()
        threads.value = if (loading) AsyncData.Loading(data) else AsyncData.Success(data)
    }

    private fun update(key: InboxThreadKey, transform: (InboxThread) -> InboxThread) {
        rows[key]?.let { rows[key] = transform(it) }
        publish(loading = threads.value.isLoading())
    }

    override suspend fun markAsRead(threads: List<InboxThreadKey>): Result<Unit> = markSnapshotAsRead(
        threads.map { key -> this.threads.value.dataOrNull()?.find { it.key == key } ?: return Result.failure(IllegalStateException("Thread unavailable")) }
    )

    override suspend fun markSnapshotAsRead(threads: List<InboxThread>): Result<Unit> = runCatchingExceptions {
        coroutineScope {
            threads.map { snapshot ->
                async {
                    receipts.withPermit {
                        val key = snapshot.key
                        (client as RustMatrixClient).threadReadActions.run(key) {
                        val inner = innerClient.getRoom(key.roomId.value) ?: error("Room is no longer joined")
                        try {
                            sendCapturedThreadReceipt(snapshot, preferences.isSendPublicReadReceiptsEnabled().first()) { type, thread, event ->
                                inner.sendSingleReceipt(type, thread, event)
                            }
                            sentReceipts.set(key, (snapshot.latestEvent ?: snapshot.rootEvent).eventId)
                            unreadStore.set(key, false).getOrThrow()
                            rowsLock.withLock {
                                update(key) {
                                    if (it.latestEvent?.eventId == snapshot.latestEvent?.eventId) {
                                        it.copy(
                                            numUnread = 0,
                                            numUnreadMentions = 0,
                                            readReceiptEventId = (snapshot.latestEvent ?: snapshot.rootEvent).eventId,
                                            isMarkedUnread = false,
                                        )
                                    } else {
                                        it
                                    }
                                }
                            }
                        } finally {
                            inner.close()
                        }
                        }
                    }
                }
            }.awaitAll()
        }
    }

    override suspend fun markAsUnread(key: InboxThreadKey): Result<Unit> = (client as RustMatrixClient).threadReadActions.run(key) {
        persistUnread(key)
    }

    private suspend fun persistUnread(key: InboxThreadKey): Result<Unit> = unreadStore.set(key, true).onSuccess {
        rowsLock.withLock { update(key) { it.copy(isMarkedUnread = true) } }
    }

    override suspend fun consumeMarkedUnread(key: InboxThreadKey): Result<Unit> = unreadStore.set(key, false).onSuccess {
        rowsLock.withLock { update(key) { it.copy(isMarkedUnread = false) } }
    }

    override suspend fun firstUnreadEventId(key: InboxThreadKey): Result<EventId?> = runCatchingExceptions {
        val item = threads.value.dataOrNull()?.find { it.key == key } ?: error("Thread is no longer available")
        if (!item.isUnread) return@runCatchingExceptions null
        val latest = (item.latestEvent ?: item.rootEvent).eventId
        synchronized(historyCache) { historyCache[key] }?.takeIf {
            it.latest == latest && it.history.effectiveReceipt == item.readReceiptEventId && it.history.complete
        }?.let { return@runCatchingExceptions it.history.firstUnread }
        val receiptIds = receipts(key)
        scan(key, receiptIds).takeIf { it.complete }?.let { return@runCatchingExceptions it.firstUnread }
        // Only an explicitly opened thread with more than MAX_UNREAD_SCAN unread replies pays for the full history.
        val room = client.getJoinedRoom(key.roomId) ?: error("Room is no longer joined")
        try {
            loadHistory(room, key.threadId.value, receiptIds).firstUnread
        } finally {
            room.close()
        }
    }
}
