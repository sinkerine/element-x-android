/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.room.threads

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.room.threads.InboxThreadKey
import io.element.android.libraries.preferences.api.store.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first

/**
 * Threaded receipts acknowledged by the homeserver but not yet in the SDK store.
 * The SDK stores receipts only from sync, and sync returns receipts only for subscribed rooms,
 * so a receipt sent from the inbox would otherwise be forgotten on the next load.
 */
@SingleIn(SessionScope::class)
@Inject
class InboxSentReceiptStore(client: MatrixClient, factory: PreferenceDataStoreFactory) {
    private val store = factory.create("elementx_threads_inbox_receipts_" + client.sessionId.value.hashCode().toUInt())

    private fun prefKey(key: InboxThreadKey) = stringPreferencesKey("${key.roomId.value}|${key.threadId.value}")

    suspend fun get(key: InboxThreadKey): EventId? = store.data.first()[prefKey(key)]?.let(::EventId)

    suspend fun set(key: InboxThreadKey, eventId: EventId) {
        store.edit { it[prefKey(key)] = eventId.value }
    }

    /** Merges the sent receipt, dropping it once sync has delivered it to the SDK store. */
    suspend fun merge(key: InboxThreadKey, synced: Set<EventId>): Set<EventId> {
        val sent = get(key) ?: return synced
        if (sent in synced) {
            store.edit { it.remove(prefKey(key)) }
            return synced
        }
        return synced + sent
    }
}
