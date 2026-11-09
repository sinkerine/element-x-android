/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.slashcommands.impl.botcommands

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.RoomMembershipState
import io.element.android.libraries.matrix.api.room.StateEventType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber

@SingleIn(RoomScope::class)
@Inject
class RoomBotCommandsSource(private val room: JoinedRoom) {
    private val cache = mutableMapOf<EventId, BotCommandDescription?>()
    private val snapshot = room.stateEventsFlow(StateEventType.Custom(BOT_COMMAND_DESCRIPTION_EVENT_TYPE))
        .map { events ->
            synchronized(cache) {
                cache.keys.retainAll(events.mapNotNull { it.eventId }.toSet())
                events.filter { it.sender != room.sessionId }.mapNotNull { event ->
                    val id = event.eventId
                    val parsed = if (id != null && cache.containsKey(id)) {
                        cache[id]
                    } else {
                        parseBotCommandDescription(event.sender, event.contentJson, event.timestamp).also {
                            if (id != null) cache[id] = it
                            if (it == null) Timber.d("Ignoring unusable bot command description from %s", event.sender)
                        }
                    }
                    parsed?.copy(stateKey = event.stateKey, eventId = id?.value.orEmpty())
                }
            }
        }
        .stateIn(room.roomCoroutineScope, SharingStarted.Eagerly, emptyList())

    internal suspend fun commands(isBuiltIn: (String) -> Boolean): List<RoomBotCommand> {
        val descriptions = snapshot.value
        val names = mutableMapOf<UserId, String>()
        for (sender in descriptions.map { it.botUserId }.distinct()) {
            try {
                val member = room.getUpdatedMember(sender).getOrThrow()
                if (member.membership == RoomMembershipState.JOIN) names[sender] = member.disambiguatedDisplayName
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Bot command member lookup failed for %s (%s)", sender, e.javaClass.simpleName)
            }
        }
        return buildRoomBotCommands(descriptions, names, isBuiltIn)
    }
}
