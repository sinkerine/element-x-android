/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.threads.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.room.threads.InboxThread
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.room.threads.FakeCrossRoomThreadsService
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadsInboxSessionTest {
    private fun TestScope.session(vararg rows: InboxThread): Pair<ThreadsInboxSession, FakeCrossRoomThreadsService> {
        val service = FakeCrossRoomThreadsService()
        service.threads.value = AsyncData.Success(rows.toList().toImmutableList())
        return ThreadsInboxSession(service, FakeMatrixClient(sessionCoroutineScope = backgroundScope)) to service
    }

    @Test
    fun `opening without receipt acknowledgement stays unread after back and visit end`() = runTest {
        val thread = previewThread()
        val (session, service) = session(thread)
        session.startVisit()
        runCurrent()
        session.opened(thread)
        runCurrent()
        assertThat(session.threads.value.dataOrNull()!!.single().isUnread).isTrue()
        assertThat(session.readState.value.optimistic).isEmpty()
        session.returnedToList()
        session.endVisit()
        runCurrent()
        assertThat(session.threads.value.dataOrNull()!!.single().isUnread).isTrue()
        assertThat(service.marked).isEmpty()
    }

    @Test
    fun `later unread waits for earlier pending receipt and survives its completion`() = runTest {
        val thread = previewThread()
        val (session, service) = session(thread)
        val receipt = kotlinx.coroutines.CompletableDeferred<Unit>()
        val actions = mutableListOf<String>()
        service.markHandler = {
            actions += "read-start"
            receipt.await()
            service.threads.value = AsyncData.Success(listOf(thread.copy(numUnread = 0, isMarkedUnread = false)).toImmutableList())
            actions += "read-complete"
            Result.success(Unit)
        }
        service.unreadHandler = {
            service.threads.value = AsyncData.Success(listOf(thread.copy(numUnread = 0, isMarkedUnread = true)).toImmutableList())
            actions += "unread"
            Result.success(Unit)
        }
        runCurrent()
        session.mark(setOf(thread.key))
        session.markUnread(thread.key)
        runCurrent()
        assertThat(actions).containsExactly("read-start")
        receipt.complete(Unit)
        runCurrent()
        assertThat(actions).containsExactly("read-start", "read-complete", "unread").inOrder()
        assertThat(session.threads.value.dataOrNull()!!.single().isMarkedUnread).isTrue()
        assertThat(session.threads.value.dataOrNull()!!.single().isUnread).isTrue()
        session.endVisit()
        runCurrent()
        assertThat(session.threads.value.dataOrNull()!!.single().isUnread).isTrue()
    }

    @Test
    fun `successful read supersedes failed unread so retry cannot replay obsolete action`() = runTest {
        val thread = previewThread()
        val (session, service) = session(thread)
        var unreadCalls = 0
        service.unreadHandler = {
            unreadCalls++
            Result.failure(IllegalStateException())
        }
        runCurrent()
        session.markUnread(thread.key)
        runCurrent()
        assertThat(session.readState.value.failedUnread).contains(thread.key)
        session.mark(setOf(thread.key))
        session.commit()
        runCurrent()
        assertThat(session.readState.value.failedUnread).isEmpty()
        service.unreadHandler = {
            unreadCalls++
            Result.success(Unit)
        }
        session.retry()
        runCurrent()
        assertThat(unreadCalls).isEqualTo(1)
    }

    @Test
    fun `undo preserves service receipts and restores all optimistic counts`() = runTest {
        val thread = previewThread()
        val (session, service) = session(thread)
        runCurrent()
        session.mark(setOf(thread.key))
        runCurrent()
        assertThat(session.threads.value.dataOrNull()!!.single().numUnread).isEqualTo(0)
        session.undo()
        runCurrent()
        advanceTimeBy(5_001)
        runCurrent()
        assertThat(service.marked).isEmpty()
        assertThat(session.threads.value.dataOrNull()!!.single().numUnread).isEqualTo(1)
    }

    @Test
    fun `second batch commits previous immutable target and undo only affects latest batch`() = runTest {
        val one = previewThread()
        val two = one.copy(key = one.key.copy(threadId = ThreadId("\$second")))
        val (session, service) = session(one, two)
        runCurrent()
        session.mark(setOf(one.key))
        session.mark(setOf(two.key))
        session.undo()
        runCurrent()
        assertThat(service.marked).containsExactly(listOf(one.key))
        assertThat(session.readState.value.pending).isEmpty()
    }

    @Test
    fun `leaving visit commits pending receipts immediately and clears visited rows`() = runTest {
        val thread = previewThread()
        val (session, service) = session(thread)
        session.startVisit()
        session.opened(thread)
        runCurrent()
        session.mark(setOf(thread.key))
        session.endVisit()
        runCurrent()
        assertThat(service.marked).containsExactly(listOf(thread.key))
        assertThat(session.visited.value).isEmpty()
        assertThat(service.visiting).isFalse()
    }

    @Test
    fun `background expires visit at five minutes and foreground cancels expiry`() = runTest {
        val thread = previewThread()
        val (session, service) = session(thread)
        session.startVisit()
        session.opened(thread)
        session.setForeground(false)
        advanceTimeBy(299_999)
        runCurrent()
        assertThat(session.visited.value).contains(thread.key)
        session.setForeground(true)
        advanceTimeBy(2)
        runCurrent()
        assertThat(session.visited.value).contains(thread.key)
        session.setForeground(false)
        advanceTimeBy(300_001)
        runCurrent()
        assertThat(session.visited.value).isEmpty()
        assertThat(service.visiting).isFalse()
        session.setForeground(true)
        assertThat(service.visiting).isTrue()
        assertThat(session.visited.value).isEmpty()
    }

    @Test
    fun `failed receipt restores unread and retry targets only failed rows`() = runTest {
        val thread = previewThread()
        val (session, service) = session(thread)
        runCurrent()
        service.markResult = Result.failure(IllegalStateException())
        session.mark(setOf(thread.key))
        session.commit()
        runCurrent()
        assertThat(session.readState.value.failed).containsExactly(thread)
        assertThat(session.threads.value.dataOrNull()!!.single().isUnread).isTrue()
        service.markResult = Result.success(Unit)
        session.retry()
        runCurrent()
        assertThat(session.readState.value.failed).isEmpty()
        assertThat(service.marked).hasSize(2)
    }

    @Test
    fun `new reply reorders only after a touched row is released`() = runTest {
        val one = previewThread()
        val two = one.copy(key = one.key.copy(threadId = ThreadId("\$second")), rootEvent = one.rootEvent.copy(timestamp = 10))
        val (session, _) = session(one, two)
        runCurrent()
        session.touch(one.key, true)
        val updated = one.copy(rootEvent = one.rootEvent.copy(timestamp = 20))
        assertThat(session.ordered(listOf(updated, two)).map { it.key }).containsExactly(two.key, one.key).inOrder()
        session.touch(one.key, false)
        assertThat(session.ordered(listOf(updated, two)).map { it.key }).containsExactly(one.key, two.key).inOrder()
    }

    @Test
    fun `badge follows optimistic actions and undo across the session`() = runTest {
        val thread = previewThread().copy(numUnreadMentions = 1)
        val (session, service) = session(thread)
        val badge = DefaultThreadsInboxBadgeProvider(session, FakeMatrixClient(sessionCoroutineScope = backgroundScope), service)
        runCurrent()
        assertThat(badge.badge.value.count).isEqualTo(1)
        assertThat(badge.badge.value.hasMention).isTrue()
        session.mark(setOf(thread.key))
        runCurrent()
        assertThat(badge.badge.value.count).isEqualTo(0)
        session.undo()
        runCurrent()
        assertThat(badge.badge.value.count).isEqualTo(1)
    }

    @Test
    fun `new reply removes optimistic read suppression without losing retained row`() = runTest {
        val thread = previewThread()
        val (session, service) = session(thread)
        runCurrent()
        session.mark(setOf(thread.key))
        val reply = thread.rootEvent.copy(eventId = io.element.android.libraries.matrix.api.core.EventId("\$new"), timestamp = 100)
        service.threads.value = AsyncData.Success(listOf(thread.copy(latestEvent = reply)).toImmutableList())
        runCurrent()
        assertThat(session.threads.value.dataOrNull()!!.single().isUnread).isTrue()
        assertThat(session.readState.value.retained).contains(thread.key)
    }

    @Test
    fun `partial failure restores only failed target and retry never resends success`() = runTest {
        val one = previewThread()
        val two = one.copy(key = one.key.copy(threadId = ThreadId("\$second")))
        val (session, service) = session(one, two)
        runCurrent()
        service.markHandler = { keys -> if (two.key in keys) Result.failure(IllegalStateException()) else Result.success(Unit) }
        session.mark(setOf(one.key, two.key))
        session.commit()
        runCurrent()
        assertThat(session.readState.value.failed.map { it.key }).containsExactly(two.key)
        assertThat(session.threads.value.dataOrNull()!!.first { it.key == one.key }.isUnread).isFalse()
        assertThat(session.threads.value.dataOrNull()!!.first { it.key == two.key }.isUnread).isTrue()
        service.markHandler = { Result.success(Unit) }
        session.retry()
        runCurrent()
        assertThat(service.marked.count { one.key in it }).isEqualTo(1)
        assertThat(service.marked.count { two.key in it }).isEqualTo(2)
    }

    @Test
    fun `commit uses event target captured before a newer reply`() = runTest {
        val one = previewThread()
        val (session, service) = session(one)
        runCurrent()
        session.mark(setOf(one.key))
        val changed = one.copy(latestEvent = one.rootEvent.copy(eventId = io.element.android.libraries.matrix.api.core.EventId("\$new")))
        service.threads.value = AsyncData.Success(listOf(changed).toImmutableList())
        runCurrent()
        session.commit()
        runCurrent()
        assertThat(service.snapshots.single()).containsExactly(one)
        assertThat(session.threads.value.dataOrNull()!!.single().isUnread).isTrue()
    }

    @Test
    fun `acknowledged optimistic target does not hide a later external mark unread`() = runTest {
        val one = previewThread()
        val (session, service) = session(one)
        runCurrent()
        session.mark(setOf(one.key))
        session.commit()
        runCurrent()
        val acknowledged = one.copy(numUnread = 0, readReceiptEventId = one.rootEvent.eventId)
        service.threads.value = AsyncData.Success(listOf(acknowledged).toImmutableList())
        runCurrent()
        assertThat(session.readState.value.optimistic).isEmpty()
        service.threads.value = AsyncData.Success(listOf(acknowledged.copy(isMarkedUnread = true)).toImmutableList())
        runCurrent()
        assertThat(session.threads.value.dataOrNull()!!.single().isUnread).isTrue()
    }

    @Test
    fun `mention only thread stays in My as dimmed visited row until visit ends`() = runTest {
        val mention = previewThread().copy(isParticipating = false, numUnreadMentions = 1)
        val (session, service) = session(mention)
        session.startVisit()
        runCurrent()
        session.opened(mention)
        service.threads.value = AsyncData.Success(listOf(mention.copy(
            numUnread = 0,
            numUnreadMentions = 0,
            readReceiptEventId = mention.rootEvent.eventId
        )).toImmutableList())
        runCurrent()
        val row = session.threads.value.dataOrNull()!!.single()
        assertThat(row.isMine).isTrue()
        assertThat(visibleThreads(listOf(row), ThreadsInboxTab.Mine, ThreadsInboxFilter.Unread, session.visited.value)).hasSize(1)
        session.endVisit()
        runCurrent()
        assertThat(session.threads.value.dataOrNull()!!.single().isMine).isFalse()
    }

    @Test
    fun `opening selects target tab and visit remains active across another start`() = runTest {
        val other = previewThread().copy(isParticipating = false)
        val (session, _) = session(other)
        session.startVisit()
        session.opened(other)
        session.startVisit()
        assertThat(session.tab.value).isEqualTo(ThreadsInboxTab.Other)
        assertThat(session.visited.value).containsExactly(other.key)
    }
}
