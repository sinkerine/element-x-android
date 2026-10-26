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
import io.element.android.libraries.matrix.api.room.CreateTimelineParams
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.threads.CrossRoomThreadsService
import io.element.android.libraries.matrix.api.room.threads.InboxThread
import io.element.android.libraries.matrix.api.room.threads.InboxThreadKey
import io.element.android.libraries.matrix.api.roomlist.RoomListService
import io.element.android.libraries.matrix.impl.RustMatrixClient
import io.element.android.libraries.matrix.impl.timeline.item.event.TimelineEventContentMapper
import io.element.android.libraries.preferences.api.store.SessionPreferencesStore
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.matrix.rustcomponents.sdk.ReceiptThread
import org.matrix.rustcomponents.sdk.ReceiptType
import org.matrix.rustcomponents.sdk.ThreadInboxRoom

/** Complete SDK account discovery; Kotlin maps receipt/history semantics without a room cutoff. */
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class)
@Inject
class KotlinCrossRoomThreadsService(
    private val client: MatrixClient,
    private val preferences: SessionPreferencesStore,
    private val unreadStore: InboxThreadUnreadStore,
    private val featureFlags: FeatureFlagService,
    private val source: ThreadInboxSource,
) : CrossRoomThreadsService {
    override val threads = MutableStateFlow<AsyncData<ImmutableList<InboxThread>>>(AsyncData.Uninitialized)
    override val successfulTimelineReads get() = (client as? RustMatrixClient)?.threadReadActions?.successfulTimelineReads
        ?: kotlinx.coroutines.flow.emptyFlow()
    private val innerClient get() = (client as RustMatrixClient).innerClient
    private val rows = mutableMapOf<RoomId, List<InboxThread>>()
    private val refreshLock = Mutex()
    private val receipts = Semaphore(4)
    private val histories = Semaphore(4)
    private val contentMapper = TimelineEventContentMapper()
    private val refreshRequests = Channel<List<String>?>(Channel.UNLIMITED)
    private var initialized = false
    private var visiting = false
    private var listRefresh: kotlinx.coroutines.Job? = null
    private var listDirty = true
    private var badgeDirty = true
    override val closedBadge = MutableStateFlow<io.element.android.libraries.matrix.api.room.threads.InboxBadgeCounts?>(null)
    private val badgeRows = mutableMapOf<RoomId, List<org.matrix.rustcomponents.sdk.ThreadInboxBadge>>()
    private data class HistoryBoundary(val latest: EventId, val receipts: Set<EventId>, val history: InboxThreadHistory)
    private val historyCache = object : LinkedHashMap<InboxThreadKey, HistoryBoundary>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<InboxThreadKey, HistoryBoundary>?): Boolean = size > 100
    }

    init {
        client.sessionCoroutineScope.launch {
            // Subscribe before the initial snapshot. Updates received during discovery
            // remain queued, including receipt-only sync and quiet-room arrivals.
            launch {
                source.updates.collect { refreshRequests.send(it) }
            }
            launch {
                client.notificationSettingsService.notificationSettingsChangeFlow.collect {
                    refreshRequests.send(null)
                }
            }
            launch {
                combine(
                    featureFlags.isFeatureEnabledFlow(FeatureFlags.CrossRoomThreads),
                    featureFlags.isFeatureEnabledFlow(FeatureFlags.Threads),
                ) { inbox, threads -> inbox && threads }.collect { enabled ->
                    if (enabled) refreshRequests.send(null)
                }
            }
            for (request in refreshRequests) {
                if (!featureFlags.isFeatureEnabled(FeatureFlags.CrossRoomThreads) ||
                    !featureFlags.isFeatureEnabled(FeatureFlags.Threads)
                ) {
                    continue
                }
                client.roomListService.state.first { it == RoomListService.State.Running }
                val changed = request?.toMutableSet()
                var full = request == null
                while (true) {
                    val next = refreshRequests.tryReceive()
                    if (next.isFailure) break
                    val ids = next.getOrNull()
                    if (ids == null) full = true else changed?.addAll(ids)
                }
                if (visiting) {
                    val job = launch { refresh(if (full || listDirty) null else changed?.toList()) }
                    listRefresh = job
                    job.join()
                    listRefresh = null
                } else {
                    refreshBadge(if (full) null else changed?.toList())
                }
            }
        }
    }

    private suspend fun refresh(request: List<String>?): Result<Unit> = refreshLock.withLock {
        runCatchingExceptions {
            // Failure preserves the last complete snapshot. Never publish the
            // first page or a partially discovered account as a successful total.
            val selected = if (initialized && !listDirty) request else null
            // An edit, redaction or decryption update can change classification
            // without changing the latest event or receipt IDs.
            synchronized(historyCache) {
                if (selected == null) historyCache.clear() else historyCache.keys.removeAll { it.roomId.value in selected }
            }
            val snapshots = source.load(selected)
            val marked = unreadStore.read()
            val mapped = coroutineScope {
                snapshots.map { snapshot -> async { histories.withPermit { mapRoom(snapshot, marked) } } }.awaitAll()
            }
            if (!visiting) return@runCatchingExceptions
            if (selected == null) rows.clear() else selected.forEach { rows.remove(RoomId(it)) }
            mapped.forEach { (id, items) -> rows[id] = items }
            // Global account data can change explicit unread without room updates.
            rows.replaceAll { _, items -> items.map { it.copy(isMarkedUnread = it.key in marked) } }
            initialized = true
            listDirty = false
            publish()
        }.onFailure { error ->
            // A later unrelated invalidation must recover this failed room too.
            listDirty = true
            timber.log.Timber.w("Inbox discovery failed: %s", error::class.java.simpleName)
            threads.value = AsyncData.Failure(error, threads.value.dataOrNull())
        }
    }

    private suspend fun mapRoom(snapshot: ThreadInboxRoom, marked: Set<InboxThreadKey>): Pair<RoomId, List<InboxThread>> {
        val id = RoomId(snapshot.roomId)
        if (snapshot.threads.isEmpty()) return id to emptyList()
        val room = client.getJoinedRoom(id) ?: error("Joined room unavailable during inbox discovery")
        try {
            val info = room.roomInfoFlow.value
            val mode = client.notificationSettingsService.getRoomNotificationSettings(
                id,
                info.isEncrypted == true,
                info.isDirect,
            ).getOrThrow().mode
            val inner = innerClient.getRoom(id.value) ?: error("Joined room unavailable during inbox discovery")
            try {
                return id to snapshot.threads.map { native ->
                    val item = native.map(contentMapper)
                    val key = InboxThreadKey(id, item.threadId)
                    // Outgoing privacy must never select the incoming read boundary.
                    val receiptIds = setOfNotNull(
                        inner.loadUserReceipt(ReceiptType.READ, ReceiptThread.Thread(item.threadId.value), client.sessionId.value)?.eventId?.let(::EventId),
                        inner.loadUserReceipt(
                            ReceiptType.READ_PRIVATE,
                            ReceiptThread.Thread(item.threadId.value),
                            client.sessionId.value
                        )?.eventId?.let(::EventId),
                    )
                    val latest = (item.latestEvent ?: item.rootEvent).eventId
                    val boundary = synchronized(historyCache) { historyCache[key] }
                    val history = boundary?.takeIf { it.latest == latest && it.receipts == receiptIds }?.history
                        ?: loadHistory(room, item.threadId.value, receiptIds).also {
                            synchronized(historyCache) { historyCache[key] = HistoryBoundary(latest, receiptIds, it) }
                        }
                    InboxThread(
                        key = key,
                        roomName = info.name,
                        roomAvatarUrl = info.avatarUrl,
                        rootEvent = item.rootEvent,
                        latestEvent = item.latestEvent,
                        numberOfReplies = item.numberOfReplies,
                        numUnread = history.unreadCount,
                        numUnreadMentions = history.mentionCount,
                        isParticipating = item.rootEvent.isOwn || history.participating,
                        roomNotificationMode = mode,
                        readReceiptEventId = history.effectiveReceipt,
                        isMarkedUnread = key in marked,
                    )
                }
            } finally {
                inner.close()
            }
        } finally {
            room.close()
        }
    }

    private suspend fun loadHistory(room: JoinedRoom, rootId: String, receiptIds: Set<EventId>): InboxThreadHistory =
        try {
            kotlinx.coroutines.withTimeout(30_000) {
                room.createTimeline(CreateTimelineParams.Threaded(io.element.android.libraries.matrix.api.core.ThreadId(rootId))).getOrThrow().use { timeline ->
                    loadInboxThreadHistory(timeline, rootId, null, client.sessionId, receiptIds)
                }
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            timber.log.Timber.w("Inbox history timed out for thread %s in room %s", rootId, room.roomId)
            throw IllegalStateException("Thread history discovery timed out", error)
        }

    private fun publish() {
        threads.value = AsyncData.Success(rows.values.flatten().sortedByDescending { it.timestamp }.toImmutableList())
    }

    override fun startVisit() {
        visiting = true
        if (!initialized || listDirty || threads.value is AsyncData.Failure) refreshRequests.trySend(null)
    }

    override fun endVisit() {
        visiting = false
        listRefresh?.cancel()
        listDirty = true
        val unread = threads.value.dataOrNull().orEmpty().filter { it.isUnread }
        closedBadge.value = io.element.android.libraries.matrix.api.room.threads.InboxBadgeCounts(unread.size, unread.any { it.numUnreadMentions > 0 })
        refreshRequests.trySend(null)
    }

    private suspend fun refreshBadge(request: List<String>?): Result<Unit> = refreshLock.withLock {
        listDirty = true
        runCatchingExceptions {
            val selected = if (badgeDirty || closedBadge.value == null || badgeRows.isEmpty()) null else request
            val compact = source.loadBadges(selected, receiptType() == ReceiptType.READ_PRIVATE)
            if (visiting) {
                refreshRequests.trySend(null)
                return@runCatchingExceptions
            }
            if (selected == null) badgeRows.clear() else selected.forEach { badgeRows.remove(RoomId(it)) }
            compact.groupBy { RoomId(it.roomId) }.forEach { (id, badges) -> badgeRows[id] = badges }
            val marked = unreadStore.read()
            var count = 0
            var mention = false
            for ((id, badges) in badgeRows) {
                val info = client.roomListService.allRooms.summaries.first().firstOrNull { it.roomId == id }?.info
                    ?: error("Badge room metadata unavailable")
                val mode = client.notificationSettingsService.getRoomNotificationSettings(id, info.isEncrypted == true, info.isDirect).getOrThrow().mode
                for (badge in badges) {
                    val key = InboxThreadKey(id, io.element.android.libraries.matrix.api.core.ThreadId(badge.threadId))
                    val unread = (badge.unread > 0uL || key in marked) && when (mode) {
                        io.element.android.libraries.matrix.api.room.RoomNotificationMode.MUTE -> false
                        io.element.android.libraries.matrix.api.room.RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY -> badge.mentions > 0uL
                        io.element.android.libraries.matrix.api.room.RoomNotificationMode.ALL_MESSAGES -> true
                    }
                    if (unread) {
                        count++
                        mention = mention || badge.mentions > 0uL
                    }
                }
            }
            closedBadge.value = io.element.android.libraries.matrix.api.room.threads.InboxBadgeCounts(count, mention)
            badgeDirty = false
        }.onFailure { error ->
            badgeDirty = true
            timber.log.Timber.w("Inbox badge refresh failed: %s", error::class.java.simpleName)
        }
    }

    // SDK discovery already exhausts every room and page; scrolling needs no rescan.
    override suspend fun paginate(): Result<Unit> = if (threads.value is AsyncData.Failure) refresh(null) else Result.success(Unit)

    private suspend fun receiptType() = if (preferences.isSendPublicReadReceiptsEnabled().first()) ReceiptType.READ else ReceiptType.READ_PRIVATE

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
                            unreadStore.set(key, false).getOrThrow()
                            refreshLock.withLock {
                                rows[key.roomId] = rows[key.roomId].orEmpty().map {
                                    if (it.key == key && it.latestEvent?.eventId == snapshot.latestEvent?.eventId) {
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
                                publish()
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
        refreshLock.withLock {
            rows[key.roomId] = rows[key.roomId].orEmpty().map { if (it.key == key) it.copy(isMarkedUnread = true) else it }
            publish()
        }
    }

    override suspend fun consumeMarkedUnread(key: InboxThreadKey): Result<Unit> = unreadStore.set(key, false).onSuccess {
        refreshLock.withLock {
            rows[key.roomId] = rows[key.roomId].orEmpty().map { if (it.key == key) it.copy(isMarkedUnread = false) else it }
            publish()
        }
    }

    override suspend fun firstUnreadEventId(key: InboxThreadKey): Result<EventId?> = runCatchingExceptions {
        val item = threads.value.dataOrNull()?.find { it.key == key } ?: error("Thread is no longer available")
        if (!item.isUnread) return@runCatchingExceptions null
        synchronized(historyCache) { historyCache[key] }?.takeIf {
            it.latest == (item.latestEvent ?: item.rootEvent).eventId && it.history.effectiveReceipt == item.readReceiptEventId
        }?.let { return@runCatchingExceptions it.history.firstUnread }
        val room = client.getJoinedRoom(key.roomId) ?: error("Room is no longer joined")
        try {
            loadHistory(room, key.threadId.value, setOfNotNull(item.readReceiptEventId)).firstUnread
        } finally {
            room.close()
        }
    }
}
