/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.room.threads

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.room.threads.InboxThreadKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A private account-data extension: explicit unread never rewinds Matrix threaded receipts. */
@SingleIn(SessionScope::class)
@Inject
class InboxThreadUnreadStore(private val client: MatrixClient) {
    private val mutex = Mutex()
    private val type = "io.element.android.threads_inbox.unread"

    suspend fun read(): Set<InboxThreadKey> = decode(client.getAccountData(type).getOrThrow())

    suspend fun set(key: InboxThreadKey, unread: Boolean): Result<Unit> = runCatchingExceptions {
        mutex.withLock {
            val keys = read().toMutableSet()
            if (unread) keys.add(key) else keys.remove(key)
            val content = buildJsonObject {
                put("threads", buildJsonArray {
                    keys.forEach { item ->
                        add(buildJsonObject {
                        put("room_id", item.roomId.value)
                        put("thread_id", item.threadId.value)
                    })
                    }
                })
            }
            client.setAccountData(type, content.toString()).getOrThrow()
        }
    }

    internal fun decode(content: String?): Set<InboxThreadKey> {
        if (content == null) return emptySet()
        return Json.parseToJsonElement(content).jsonObject["threads"]?.jsonArray.orEmpty().mapNotNull { entry ->
            val roomId = entry.jsonObject["room_id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val threadId = entry.jsonObject["thread_id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            InboxThreadKey(RoomId(roomId), ThreadId(threadId))
        }.toSet()
    }
}
