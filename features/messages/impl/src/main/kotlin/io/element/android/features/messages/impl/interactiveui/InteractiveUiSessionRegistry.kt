/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.interactiveui

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.messages.impl.timeline.model.event.InteractiveUiTimelineModel
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemInteractiveUiContent
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.interactiveui.core.action.InteractiveUiEventContext
import io.element.android.libraries.interactiveui.core.protocol.toJsonElement
import io.element.android.libraries.interactiveui.core.session.InteractiveUiSession
import io.element.android.libraries.interactiveui.core.session.InteractiveUiSessionState
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.room.JoinedRoom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@SingleIn(RoomScope::class)
@Inject
class InteractiveUiSessionRegistry(private val room: JoinedRoom, private val store: InteractiveUiStateStore) {
    private val sessions = mutableMapOf<EventId, Entry>()

    init {
        // Room ownership keeps sends alive while items leave composition, then flushes values on room disposal.
        room.roomCoroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    sessions.forEach { (eventId, entry) ->
                        val session = entry.session
                        session.dispose()
                        val state = session.state.value
                        store.save(room.roomId, eventId, buildJsonObject {
                            put("ui_id", state.payload.uiId)
                            put("revision", state.payload.revision)
                            put("state", JsonObject(state.values.mapValues { it.value.toJsonElement() }))
                        })
                    }
                    sessions.clear()
                }
            }
        }
    }

    fun sessionFor(content: TimelineItemInteractiveUiContent): Entry {
        val model = content.model as InteractiveUiTimelineModel.Valid
        return sessions[content.eventId]?.apply { onRevision(model) } ?: run {
            val saved = store.peek(room.roomId, content.eventId)
            val session = InteractiveUiSession(
                context = InteractiveUiEventContext(content.eventId.value, content.senderId.value, content.threadRootId?.value),
                initialPayload = model.payload,
                savedState = saved,
                scope = room.roomCoroutineScope,
                sendAction = { room.sendRaw("m.room.message", it.toString()).map { } },
                persistState = { store.save(room.roomId, content.eventId, it) },
            )
            val entry = Entry(session, model, room.roomCoroutineScope)
            sessions[content.eventId] = entry
            if (saved == null) {
                room.roomCoroutineScope.launch {
                    store.load(room.roomId, content.eventId)?.let(session::applySavedState)
                }
            }
            entry
        }
    }

    data class State(val sessionState: InteractiveUiSessionState, val model: InteractiveUiTimelineModel.Valid)

    class Entry(val session: InteractiveUiSession, model: InteractiveUiTimelineModel.Valid, scope: CoroutineScope) {
        private val mutableState = MutableStateFlow(State(session.state.value, model))
        val state = mutableState.asStateFlow()

        init {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { session.state.collect(::publish) }
        }

        @Synchronized
        fun onRevision(model: InteractiveUiTimelineModel.Valid) {
            if (model.payload.revision <= session.state.value.payload.revision) return
            session.onRevision(model.payload)
            val acceptedState = session.state.value
            if (acceptedState.payload === model.payload) {
                mutableState.value = State(acceptedState, model)
            }
        }

        @Synchronized
        private fun publish(sessionState: InteractiveUiSessionState) {
            // A revision is published only once its matching rich text and media have been accepted.
            if (sessionState.payload === mutableState.value.model.payload) {
                mutableState.value = mutableState.value.copy(sessionState = sessionState)
            }
        }
    }
}
