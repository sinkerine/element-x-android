/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.element.android.features.messages.impl.interactiveui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.preferences.api.store.PreferenceDataStoreFactory
import io.element.android.libraries.sessionstorage.test.observer.FakeSessionObserver
import io.element.android.services.toolbox.test.systemclock.FakeSystemClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import androidx.datastore.preferences.core.PreferenceDataStoreFactory as AndroidPreferenceDataStoreFactory

class DataStoreInteractiveUiStateStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val roomId = RoomId("!room:test")
    private val eventId = EventId("$" + "event")
    private fun envelope(value: String) = buildJsonObject {
        put("ui_id", "test")
        put("revision", 0)
        put("state", buildJsonObject { put("name", value) })
    }

    @Test
    fun `round trip survives a new store and isolates room keys`() = runTest {
        val dataStore = AndroidPreferenceDataStoreFactory.create(scope = backgroundScope) { temporaryFolder.newFile("state.preferences_pb") }
        val factory = object : PreferenceDataStoreFactory {
            override fun create(name: String): DataStore<Preferences> {
                assertThat(name).startsWith("elementx_interactive_ui_")
                assertThat(name).doesNotContain("@")
                return dataStore
            }
        }
        val client = FakeMatrixClient()
        val observer = FakeSessionObserver()
        val first = DataStoreInteractiveUiStateStore(client, factory, observer, FakeSystemClock(), backgroundScope)
        assertThat(first.peek(roomId, eventId)).isNull()
        val expected = envelope("Restored")
        first.save(roomId, eventId, expected)
        assertThat(first.peek(roomId, eventId)).isEqualTo(expected)
        assertThat(first.load(RoomId("!other:test"), eventId)).isNull()
        val restarted = DataStoreInteractiveUiStateStore(client, factory, observer, FakeSystemClock(), backgroundScope)
        assertThat(restarted.load(roomId, eventId)).isEqualTo(expected)
    }

    @Test
    fun `64 KB bound applies to UTF8 bytes and corrupt entries are ignored`() = runTest {
        val dataStore = AndroidPreferenceDataStoreFactory.create(scope = backgroundScope) { temporaryFolder.newFile("caps.preferences_pb") }
        val factory = object : PreferenceDataStoreFactory {
            override fun create(name: String) = dataStore
        }
        val store = DataStoreInteractiveUiStateStore(FakeMatrixClient(), factory, FakeSessionObserver(), FakeSystemClock(), backgroundScope)
        store.save(roomId, eventId, envelope("a".repeat(64_000)))
        assertThat(store.load(roomId, eventId)).isNotNull()
        store.save(roomId, eventId, envelope("a".repeat(65_536)))
        assertThat(store.load(roomId, eventId)).isEqualTo(envelope("a".repeat(64_000)))
        store.save(roomId, EventId("$" + "unicode"), envelope("界".repeat(22_000)))
        assertThat(store.load(roomId, EventId("$" + "unicode"))).isNull()
        for ((suffix, raw) in listOf("broken" to "{", "wrong" to "[]", "envelope" to """{"timestamp":0,"envelope":{"ui_id":[],"revision":{},"state":{}}}""")) {
            dataStore.edit { it[stringPreferencesKey("${roomId.value}|$" + suffix)] = raw }
            assertThat(store.load(roomId, EventId("$" + suffix))).isNull()
        }
    }

    @Test
    fun `200 entry eviction removes oldest and update refreshes age`() = runTest {
        val dataStore = AndroidPreferenceDataStoreFactory.create(scope = backgroundScope) { temporaryFolder.newFile("eviction.preferences_pb") }
        val factory = object : PreferenceDataStoreFactory {
            override fun create(name: String) = dataStore
        }
        val clock = FakeSystemClock(0)
        val store = DataStoreInteractiveUiStateStore(FakeMatrixClient(), factory, FakeSessionObserver(), clock, backgroundScope)
        for (index in 0 until 200) {
            clock.epochMillisResult = index.toLong()
            store.save(roomId, EventId("$" + "event$index"), envelope("$index"))
        }
        clock.epochMillisResult = 200
        store.save(roomId, EventId("$" + "event0"), envelope("refreshed"))
        clock.epochMillisResult = 201
        store.save(roomId, EventId("$" + "event200"), envelope("new"))
        assertThat(dataStore.data.first().asMap()).hasSize(200)
        assertThat(store.load(roomId, EventId("$" + "event1"))).isNull()
        assertThat(store.load(roomId, EventId("$" + "event0"))).isEqualTo(envelope("refreshed"))
        assertThat(store.load(roomId, EventId("$" + "event200"))).isEqualTo(envelope("new"))
    }

    @Test
    fun `session deletion clears disk and memory and refuses subsequent saves`() = runTest {
        val dataStore = AndroidPreferenceDataStoreFactory.create(scope = backgroundScope) { temporaryFolder.newFile("deleted.preferences_pb") }
        val factory = object : PreferenceDataStoreFactory {
            override fun create(name: String) = dataStore
        }
        val observer = FakeSessionObserver()
        val client = FakeMatrixClient()
        val store = DataStoreInteractiveUiStateStore(client, factory, observer, FakeSystemClock(), backgroundScope)
        store.save(roomId, eventId, envelope("draft"))
        observer.onSessionDeleted("@other:test")
        assertThat(store.peek(roomId, eventId)).isNotNull()
        observer.onSessionDeleted(client.sessionId.value)
        assertThat(store.peek(roomId, eventId)).isNull()
        assertThat(dataStore.data.first().asMap()).isEmpty()
        store.save(roomId, eventId, envelope("late"))
        runCurrent()
        assertThat(store.load(roomId, eventId)).isNull()
        assertThat(observer.listeners).isEmpty()
    }
}
