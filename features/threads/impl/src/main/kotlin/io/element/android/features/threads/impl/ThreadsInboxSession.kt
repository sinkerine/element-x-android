/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.threads.impl

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.architecture.map
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.room.threads.CrossRoomThreadsService
import io.element.android.libraries.matrix.api.room.threads.InboxThread
import io.element.android.libraries.matrix.api.room.threads.InboxThreadKey
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** The visit outlives list compositions and room nodes. A batch always owns immutable event targets. */
@SingleIn(SessionScope::class)
@Inject
class ThreadsInboxSession(private val service: CrossRoomThreadsService, private val client: MatrixClient) {
    data class ReadState(
        val pending: List<InboxThread> = emptyList(),
        val optimistic: Map<InboxThreadKey, InboxThread> = emptyMap(),
        val failed: List<InboxThread> = emptyList(),
        val retained: Set<InboxThreadKey> = emptySet(),
        val failedUnread: Set<InboxThreadKey> = emptySet(),
        val retainedMine: Set<InboxThreadKey> = emptySet(),
    )

    val readState = MutableStateFlow(ReadState())
    val visited = MutableStateFlow<Set<InboxThreadKey>>(emptySet())
    val tab = MutableStateFlow(ThreadsInboxTab.Mine)
    val filter = MutableStateFlow(ThreadsInboxFilter.Unread)
    private var timer: Job? = null
    private var expiry: Job? = null
    private var active = false
    private var openedAtNanos: Long? = null
    private val sends = Semaphore(4)
    private var selected = false
    private val touched = mutableSetOf<InboxThreadKey>()
    private var frozenOrder: List<InboxThreadKey>? = null
    val orderRevision = MutableStateFlow(0)

    fun touch(key: InboxThreadKey, pressed: Boolean) {
        if (pressed) {
            if (touched.isEmpty()) frozenOrder = threads.value.dataOrNull().orEmpty().sortedByDescending { it.timestamp }.map { it.key }
            touched += key
        } else {
            touched -= key
            if (touched.isEmpty()) {
                frozenOrder = null
                orderRevision.value++
            }
        }
    }

    fun ordered(items: List<InboxThread>): List<InboxThread> {
        val order = frozenOrder ?: return items.sortedByDescending { it.timestamp }
        return items.sortedBy { order.indexOf(it.key).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
    }
    val threads = combine(service.threads, readState) { data, state ->
        data.map { items ->
            items.map { item ->
                val target = state.optimistic[item.key]
                if (target != null && target.latestEvent?.eventId == item.latestEvent?.eventId) {
                    item.copy(
                        numUnread = 0,
                        numUnreadMentions = 0,
                        isMarkedUnread = false,
                        isParticipating = item.isParticipating || item.key in state.retainedMine,
                    )
                } else if (!item.isUnread && item.key in state.retainedMine) {
                    item.copy(isParticipating = true)
                } else {
                    item
                }
            }.toImmutableList()
        }
    }.stateIn(client.sessionCoroutineScope, SharingStarted.Eagerly, service.threads.value)

    init {
        client.sessionCoroutineScope.launch {
            service.threads.collect { data ->
                val items = data.dataOrNull() ?: return@collect
                val state = readState.value
                val acknowledged = state.optimistic.filter { (key, target) ->
                    val current = items.find { it.key == key }
                    current == null || (!current.isUnread && current.readReceiptEventId == (target.latestEvent ?: target.rootEvent).eventId)
                }.keys
                if (acknowledged.isNotEmpty()) readState.value = state.copy(optimistic = state.optimistic - acknowledged)
            }
        }
    }

    fun startVisit() {
        selected = true
        if (active) return
        active = true
        openedAtNanos = System.nanoTime()
        service.startVisit()
    }

    fun rowsDisplayed() {
        val start = openedAtNanos ?: return
        openedAtNanos = null
        timber.log.Timber.d("Threads inbox rows displayed after %d ms", (System.nanoTime() - start) / 1_000_000)
    }

    fun endVisit() {
        selected = false
        commit()
        expiry?.cancel()
        active = false
        visited.value = emptySet()
        touched.clear()
        frozenOrder = null
        // Keep in-flight optimistic targets until their individual result is known.
        readState.value = readState.value.copy(retained = emptySet(), retainedMine = emptySet())
        service.endVisit()
    }

    fun setForeground(foreground: Boolean) {
        expiry?.cancel()
        if (foreground && selected && !active) startVisit()
        if (!foreground && active) {
            expiry = client.sessionCoroutineScope.launch {
                delay(300_000)
                endVisit()
                // Home still has Threads selected; foreground starts a fresh visit.
                selected = true
            }
        }
    }

    fun opened(thread: InboxThread) {
        readState.value = readState.value.copy(
            optimistic = readState.value.optimistic + (thread.key to thread),
            retainedMine = if (thread.isMine) readState.value.retainedMine + thread.key else readState.value.retainedMine,
        )
        visited.value += thread.key
        tab.value = if (thread.isMine) ThreadsInboxTab.Mine else ThreadsInboxTab.Other
    }

    fun mark(keys: Set<InboxThreadKey>) {
        commit()
        val targets = threads.value.dataOrNull().orEmpty().filter { it.key in keys }
        if (targets.isEmpty()) return
        val state = readState.value
        readState.value = state.copy(
            pending = targets,
            optimistic = state.optimistic + targets.associateBy { it.key },
            retained = state.retained + keys,
            retainedMine = state.retainedMine + targets.filter { it.isMine }.map { it.key },
        )
        timer = client.sessionCoroutineScope.launch {
            delay(5_000)
            commit()
        }
    }

    fun markUnread(key: InboxThreadKey) {
        commit()
        readState.value = readState.value.copy(optimistic = readState.value.optimistic - key)
        client.sessionCoroutineScope.launch {
            service.markAsUnread(key).onFailure {
                readState.value = readState.value.copy(failedUnread = readState.value.failedUnread + key)
            }
        }
    }

    fun undo() {
        timer?.cancel()
        val state = readState.value
        readState.value = state.copy(
            pending = emptyList(),
            optimistic = state.optimistic - state.pending.map { it.key }.toSet(),
            retained = state.retained - state.pending.map { it.key }.toSet(),
        )
    }

    fun commit() {
        timer?.cancel()
        val targets = readState.value.pending
        if (targets.isEmpty()) return
        readState.value = readState.value.copy(pending = emptyList())
        send(targets)
    }

    fun retry() {
        val targets = readState.value.failed
        val unread = readState.value.failedUnread
        readState.value = readState.value.copy(failed = emptyList(), failedUnread = emptySet())
        send(targets)
        unread.forEach(::markUnread)
    }

    private fun send(targets: List<InboxThread>) {
        // Per-target results restore only failed rows; successful receipts must not be retried as a batch.
        targets.forEach { target ->
            client.sessionCoroutineScope.launch {
                val result = sends.withPermit { service.markSnapshotAsRead(listOf(target)) }
                val state = readState.value
                val sameTarget = state.optimistic[target.key] == target
                readState.value = state.copy(
                    optimistic = if (sameTarget && result.isFailure) state.optimistic - target.key else state.optimistic,
                    failed = if (result.isFailure) {
                        state.failed.filterNot {
                        it.key == target.key
                    } + target
                    } else {
                        state.failed.filterNot { it.key == target.key }
                    },
                )
            }
        }
    }
}
