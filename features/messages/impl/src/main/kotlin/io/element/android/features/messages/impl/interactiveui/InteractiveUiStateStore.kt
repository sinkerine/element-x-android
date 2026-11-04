/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.interactiveui

import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

interface InteractiveUiStateStore {
    fun peek(roomId: RoomId, eventId: EventId): JsonElement?
    suspend fun load(roomId: RoomId, eventId: EventId): JsonElement?
    suspend fun save(roomId: RoomId, eventId: EventId, envelope: JsonObject)
}
