/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.interactiveui

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.androidutils.hash.hash
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.preferences.api.store.PreferenceDataStoreFactory
import io.element.android.libraries.sessionstorage.api.observer.SessionListener
import io.element.android.libraries.sessionstorage.api.observer.SessionObserver
import io.element.android.services.toolbox.api.systemclock.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import timber.log.Timber

@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class)
class DataStoreInteractiveUiStateStore(
    client: MatrixClient,
    factory: PreferenceDataStoreFactory,
    sessionObserver: SessionObserver,
    private val systemClock: SystemClock,
    @SessionCoroutineScope sessionCoroutineScope: CoroutineScope,
) : InteractiveUiStateStore {
    private val sessionId = client.sessionId.value
    private val dataStore = factory.create("elementx_interactive_ui_${sessionId.hash().take(16)}")
    private val mutex = Mutex()
    @Volatile private var snapshot: Map<String, JsonObject> = emptyMap()
    private var deleted = false
    private val listener = object : SessionListener {
        override suspend fun onSessionDeleted(userId: String, wasLastSession: Boolean) {
            if (userId != sessionId) return
            safely {
                mutex.withLock {
                    deleted = true
                    snapshot = emptyMap()
                    dataStore.edit { it.clear() }
                }
            }
            sessionObserver.removeListener(this)
        }
    }

    init {
        sessionObserver.addListener(listener)
        sessionCoroutineScope.launch { safely { mutex.withLock { if (!deleted) snapshot = read(dataStore.data.first()) } } }
    }

    override fun peek(roomId: RoomId, eventId: EventId): JsonElement? = snapshot[key(roomId, eventId)]?.get("envelope")

    override suspend fun load(roomId: RoomId, eventId: EventId): JsonElement? {
        safely { mutex.withLock { if (!deleted) snapshot = read(dataStore.data.first()) } }
        return peek(roomId, eventId)
    }

    override suspend fun save(roomId: RoomId, eventId: EventId, envelope: JsonObject) {
        val entry = buildJsonObject {
            put("timestamp", systemClock.epochMillis())
            put("envelope", envelope)
        }.toString()
        if (entry.toByteArray(Charsets.UTF_8).size > MAX_ENTRY_BYTES) return
        safely {
            mutex.withLock {
                if (deleted) return@withLock
                val updated = dataStore.edit { preferences ->
                    val entries = read(preferences).toMutableMap()
                    entries.remove(key(roomId, eventId))
                    entries[key(roomId, eventId)] = Json.parseToJsonElement(entry) as JsonObject
                    val retained = entries.entries.sortedBy { it.value["timestamp"]?.jsonPrimitive?.longOrNull ?: 0L }.takeLast(MAX_ENTRIES)
                    preferences.clear()
                    retained.forEach { (key, value) -> preferences[stringPreferencesKey(key)] = value.toString() }
                }
                snapshot = read(updated)
            }
        }
    }

    private fun read(preferences: Preferences): Map<String, JsonObject> = preferences.asMap().entries.mapNotNull { (key, value) ->
        val raw = value as? String ?: return@mapNotNull null
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_ENTRY_BYTES) return@mapNotNull null
        val entry = runCatchingExceptions { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return@mapNotNull null
        val envelope = entry["envelope"] as? JsonObject ?: return@mapNotNull null
        if ((envelope["ui_id"] as? JsonPrimitive)?.isString != true) return@mapNotNull null
        if ((envelope["revision"] as? JsonPrimitive)?.longOrNull == null) return@mapNotNull null
        if (envelope["state"] !is JsonObject) return@mapNotNull null
        if ((entry["timestamp"] as? JsonPrimitive)?.longOrNull == null) return@mapNotNull null
        key.name to entry
    }.sortedBy { it.second["timestamp"]?.jsonPrimitive?.longOrNull }.takeLast(MAX_ENTRIES).toMap()

    private suspend fun safely(block: suspend () -> Unit) {
        runCatchingExceptions { block() }.onFailure { Timber.w("interactive_ui store failed: %s", it.javaClass.simpleName) }
    }

    private fun key(roomId: RoomId, eventId: EventId) = "${roomId.value}|${eventId.value}"

    private companion object {
        const val MAX_ENTRY_BYTES = 65_536
        const val MAX_ENTRIES = 200
    }
}
