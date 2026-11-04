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
import java.lang.ref.WeakReference

@SingleIn(RoomScope::class)
@Inject
class InteractiveUiSessionRegistry(room: JoinedRoom, store: InteractiveUiStateStore) {
    private val roomSessions = sessionsForRoom(room, store)

    fun sessionFor(content: TimelineItemInteractiveUiContent): Entry = roomSessions.sessionFor(content)

    private companion object {
        // Activity recreation rebuilds RoomScope graphs but retains JoinedRoom. Weak references avoid retaining
        // rooms/accounts globally; the room coroutine owns the entries until cancellation and flushes them once.
        val owners = mutableListOf<WeakReference<RoomSessions>>()

        @Synchronized
        fun sessionsForRoom(room: JoinedRoom, store: InteractiveUiStateStore): RoomSessions {
            owners.removeAll { it.get() == null }
            return owners.firstNotNullOfOrNull { it.get()?.takeIf { owner -> owner.room === room } }
                ?: RoomSessions(room, store).also { owner ->
                    owners.add(WeakReference(owner))
                    owner.start()
                }
        }

        @Synchronized
        fun release(owner: RoomSessions) {
            owners.removeAll { it.get() == null || it.get() === owner }
        }
    }

    private class RoomSessions(val room: JoinedRoom, private val store: InteractiveUiStateStore) {
        private val sessions = mutableMapOf<EventId, Entry>()

        fun start() {
            room.roomCoroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    release(this@RoomSessions)
                    withContext(NonCancellable) {
                        try {
                            sessions.forEach { (eventId, entry) ->
                                entry.session.dispose()
                                store.save(room.roomId, eventId, entry.session.savedStateEnvelope())
                            }
                        } finally {
                            sessions.clear()
                        }
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
