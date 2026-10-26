/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.room.threads

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.featureflag.test.FakeFeatureFlagService
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.roomlist.RoomListService
import io.element.android.libraries.matrix.impl.fixtures.factories.aRustTimelineItemContentMsgLike
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.roomlist.FakeRoomListService
import io.element.android.libraries.preferences.test.InMemorySessionPreferencesStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.matrix.rustcomponents.sdk.ProfileDetails
import org.matrix.rustcomponents.sdk.ThreadInboxPage
import org.matrix.rustcomponents.sdk.ThreadInboxPageRoom
import org.matrix.rustcomponents.sdk.ThreadInboxPageThread
import org.matrix.rustcomponents.sdk.ThreadInboxUnread
import org.matrix.rustcomponents.sdk.ThreadListItem
import org.matrix.rustcomponents.sdk.ThreadListItemEvent

@OptIn(ExperimentalCoroutinesApi::class)
class KotlinCrossRoomThreadsServiceTest {
    private class Source : ThreadInboxSource {
        val invalidations = Channel<List<String>?>(Channel.UNLIMITED)
        override val updates = invalidations.receiveAsFlow()
        var resets = 0
        var calls = 0
        var more = true
        var fail = false
        var gate: CompletableDeferred<Unit>? = null
        var page: List<ThreadInboxPageRoom> = emptyList()
        val receiptIds = mutableMapOf<ThreadId, Set<EventId>>()
        val scans = mutableListOf<ThreadId>()
        val scanGates = mutableMapOf<ThreadId, CompletableDeferred<ThreadInboxUnread>>()
        override fun reset() {
            resets++
        }
        override suspend fun nextPage(): ThreadInboxPage {
            calls++
            gate?.await()
            check(!fail)
            return ThreadInboxPage(page, more)
        }
        override suspend fun receipts(roomId: RoomId, threadId: ThreadId) = receiptIds[threadId].orEmpty()
        override suspend fun unread(roomId: RoomId, threadId: ThreadId, receipts: Set<EventId>): ThreadInboxUnread {
            scans += threadId
            return scanGates[threadId]?.await() ?: ThreadInboxUnread(2u, 1u, "\$first", receipts.firstOrNull()?.value, false, true)
        }
    }

    private fun event(id: String, own: Boolean) = ThreadListItemEvent(
        eventId = id,
        timestamp = 1u,
        sender = if (own) "@me:server" else "@alice:server",
        senderProfile = ProfileDetails.Pending,
        isOwn = own,
        content = aRustTimelineItemContentMsgLike(),
    )

    private fun thread(root: String, latest: String?, latestOwn: Boolean = false, participated: Boolean = false) = ThreadInboxPageThread(
        item = ThreadListItem(rootEvent = event(root, false), latestEvent = latest?.let { event(it, latestOwn) }, numReplies = if (latest == null) 0u else 3u),
        participated = participated,
    )

    private val roomId = RoomId("!room:server")

    /** Store IO on the test scheduler keeps runCurrent deterministic. */
    private fun kotlinx.coroutines.test.TestScope.testDataStores() = object : io.element.android.libraries.preferences.api.store.PreferenceDataStoreFactory {
        override fun create(name: String) = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = backgroundScope) {
            java.io.File.createTempFile("test", ".preferences_pb").also { it.delete() }
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.service(
        source: Source,
        sentReceipts: (FakeMatrixClient) -> InboxSentReceiptStore = { InboxSentReceiptStore(it, testDataStores()) },
    ): KotlinCrossRoomThreadsService {
        val rooms = FakeRoomListService()
        val client = FakeMatrixClient(sessionCoroutineScope = backgroundScope, roomListService = rooms, getAccountDataLambda = { Result.success(null) })
        client.givenGetRoomResult(roomId, FakeJoinedRoom())
        rooms.postState(RoomListService.State.Running)
        return newService(client, source, sentReceipts(client))
    }

    private fun kotlinx.coroutines.test.TestScope.newService(
        client: FakeMatrixClient,
        source: Source,
        sentReceipts: InboxSentReceiptStore = InboxSentReceiptStore(client, testDataStores()),
    ) = KotlinCrossRoomThreadsService(client, InMemorySessionPreferencesStore(), InboxThreadUnreadStore(client), FakeFeatureFlagService(), source, sentReceipts)

    @Test
    fun `receipt sent from the inbox keeps the thread read before sync stores it`() = runTest {
        val source = Source().apply {
            page = listOf(ThreadInboxPageRoom(roomId.value, listOf(thread("\$root", "\$latest", participated = true))))
            receiptIds[ThreadId("\$root")] = setOf(EventId("\$old"))
        }
        val key = io.element.android.libraries.matrix.api.room.threads.InboxThreadKey(roomId, ThreadId("\$root"))
        lateinit var store: InboxSentReceiptStore
        val service = service(source) { client -> InboxSentReceiptStore(client, testDataStores()).also { store = it } }
        store.set(key, EventId("\$latest"))
        service.startVisit()
        runCurrent()
        assertThat(source.scans).isEmpty()
        with(service.threads.value.dataOrNull()!!.single()) {
            assertThat(isUnread).isFalse()
            assertThat(readReceiptEventId).isEqualTo(EventId("\$latest"))
        }
        // Once sync delivers the receipt, the local copy is dropped.
        assertThat(store.merge(key, setOf(EventId("\$latest")))).containsExactly(EventId("\$latest"))
        assertThat(store.get(key)).isNull()
    }

    @Test
    fun `read rows need no reply history and only unread rows are scanned`() = runTest {
        val source = Source().apply {
            page = listOf(
                ThreadInboxPageRoom(
                    roomId.value,
                    listOf(
                        thread("\$read", "\$readLatest"),
                        thread("\$own", "\$ownLatest", latestOwn = true),
                        thread("\$empty", null),
                        thread("\$unread", "\$unreadLatest", participated = true),
                    ),
                ),
            )
            receiptIds[ThreadId("\$read")] = setOf(EventId("\$readLatest"))
            receiptIds[ThreadId("\$unread")] = setOf(EventId("\$receipt"))
        }
        val service = service(source)
        service.startVisit()
        runCurrent()
        assertThat(source.scans).containsExactly(ThreadId("\$unread"))
        val rows = service.threads.value.dataOrNull()!!.associateBy { it.key.threadId.value }
        assertThat(rows.values.filter { it.isUnread }.map { it.key.threadId.value }).containsExactly("\$unread")
        assertThat(rows.getValue("\$own").isParticipating).isTrue()
        with(rows.getValue("\$unread")) {
            assertThat(numUnread).isEqualTo(2)
            assertThat(numUnreadMentions).isEqualTo(1)
            // Participation comes from the server's bundled summary.
            assertThat(isParticipating).isTrue()
            assertThat(readReceiptEventId).isEqualTo(EventId("\$receipt"))
        }
        assertThat(service.firstUnreadEventId(rows.getValue("\$unread").key).getOrThrow()).isEqualTo(EventId("\$first"))
        assertThat(source.scans).hasSize(1)
    }

    @Test
    fun `rows are published before slow scans finish and a failed scan keeps other rows`() = runTest {
        val slow = CompletableDeferred<ThreadInboxUnread>()
        val source = Source().apply {
            page = listOf(ThreadInboxPageRoom(roomId.value, listOf(thread("\$read", "\$latest"), thread("\$slow", "\$slowLatest"))))
            receiptIds[ThreadId("\$read")] = setOf(EventId("\$latest"))
            scanGates[ThreadId("\$slow")] = slow
        }
        val service = service(source)
        service.startVisit()
        runCurrent()
        assertThat(service.threads.value.isLoading()).isTrue()
        assertThat(service.threads.value.dataOrNull()!!.map { it.key.threadId.value }).containsExactly("\$read")
        slow.completeExceptionally(IllegalStateException("network"))
        runCurrent()
        assertThat(service.threads.value.isFailure()).isTrue()
        assertThat(service.threads.value.dataOrNull()!!.map { it.key.threadId.value }).containsExactly("\$read")
        assertThat(service.hasMore.value).isTrue()
        source.scanGates.clear()
        service.paginate().getOrThrow()
        assertThat(source.calls).isEqualTo(1)
        assertThat(service.threads.value.dataOrNull()!!.map { it.key.threadId.value }).containsExactly("\$read", "\$slow")
    }

    @Test
    fun `closed inbox never scans and opening loads only one page`() = runTest {
        val rooms = FakeRoomListService()
        val client = FakeMatrixClient(sessionCoroutineScope = backgroundScope, roomListService = rooms, getAccountDataLambda = { Result.success(null) })
        val source = Source()
        val service = newService(client, source)
        rooms.postState(RoomListService.State.Running)
        runCurrent()
        assertThat(source.calls).isEqualTo(0)
        service.startVisit()
        runCurrent()
        assertThat(source.calls).isEqualTo(1)
        assertThat(service.hasMore.value).isTrue()
        assertThat(service.threads.value.isSuccess()).isTrue()
        runCurrent()
        assertThat(source.calls).isEqualTo(1)
        service.paginate().getOrThrow()
        assertThat(source.calls).isEqualTo(2)
        service.endVisit()
        source.invalidations.send(null)
        runCurrent()
        assertThat(source.calls).isEqualTo(2)
    }

    @Test
    fun `exhausted cursor ignores more requests and warm visit retains page`() = runTest {
        val rooms = FakeRoomListService()
        val client = FakeMatrixClient(sessionCoroutineScope = backgroundScope, roomListService = rooms, getAccountDataLambda = { Result.success(null) })
        val source = Source().apply { more = false }
        val service = newService(client, source)
        rooms.postState(RoomListService.State.Running)
        runCurrent()
        service.startVisit()
        runCurrent()
        assertThat(service.hasMore.value).isFalse()
        service.paginate().getOrThrow()
        service.endVisit()
        service.startVisit()
        runCurrent()
        assertThat(source.calls).isEqualTo(1)
    }

    @Test
    fun `failure preserves data and retries same cursor without reset`() = runTest {
        val rooms = FakeRoomListService()
        val client = FakeMatrixClient(sessionCoroutineScope = backgroundScope, roomListService = rooms, getAccountDataLambda = { Result.success(null) })
        val source = Source()
        val service = newService(client, source)
        rooms.postState(RoomListService.State.Running)
        service.startVisit()
        runCurrent()
        source.fail = true
        assertThat(service.paginate().isFailure).isTrue()
        assertThat(service.threads.value.isFailure()).isTrue()
        assertThat(service.threads.value.dataOrNull()).isNotNull()
        source.fail = false
        service.paginate().getOrThrow()
        assertThat(service.threads.value.isSuccess()).isTrue()
        assertThat(source.resets).isEqualTo(1)
    }

    @Test
    fun `closing during a page cancels it and reopening resumes loading`() = runTest {
        val rooms = FakeRoomListService()
        val client = FakeMatrixClient(sessionCoroutineScope = backgroundScope, roomListService = rooms, getAccountDataLambda = { Result.success(null) })
        val source = Source().apply { gate = CompletableDeferred() }
        val service = newService(client, source)
        rooms.postState(RoomListService.State.Running)
        service.startVisit()
        runCurrent()
        service.endVisit()
        runCurrent()
        source.gate!!.complete(Unit)
        service.startVisit()
        runCurrent()
        assertThat(service.threads.value.isSuccess()).isTrue()
    }

    @Test
    fun `initial page waits for sync without starting discovery`() = runTest {
        val rooms = FakeRoomListService()
        val client = FakeMatrixClient(sessionCoroutineScope = backgroundScope, roomListService = rooms, getAccountDataLambda = { Result.success(null) })
        val source = Source()
        val service = newService(client, source)
        service.startVisit()
        runCurrent()
        assertThat(source.calls).isEqualTo(0)
        rooms.postState(RoomListService.State.Running)
        runCurrent()
        assertThat(source.calls).isEqualTo(1)
    }
}
