/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.room

import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.MatrixPatterns
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.room.RoomStateEvent
import timber.log.Timber
import org.matrix.rustcomponents.sdk.RoomStateEvent as RustRoomStateEvent

internal fun RustRoomStateEvent.map(): RoomStateEvent? {
    if (!MatrixPatterns.isUserId(sender)) {
        Timber.w("Dropping room state event with invalid sender")
        return null
    }
    return RoomStateEvent(
        eventType = eventType.map(),
        stateKey = stateKey,
        sender = UserId(sender),
        contentJson = contentJson,
        eventId = eventId?.let(::EventId),
        timestamp = timestamp?.toLong(),
    )
}
