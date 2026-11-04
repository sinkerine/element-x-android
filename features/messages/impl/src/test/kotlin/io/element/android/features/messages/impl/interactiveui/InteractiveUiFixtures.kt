/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.interactiveui

import io.element.android.features.messages.impl.timeline.model.event.InteractiveUiTimelineModel
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemInteractiveUiContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemTextContent
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiConstants
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiParseResult
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiParser
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.core.UserId
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal val interactiveUiPayloadJson = """{
    "version":1,"ui_id":"test","revision":0,"status":"active","title":"Card header",
    "state":{"name":"","on":false},"root":"root","nodes":[
    {"id":"root","type":"stack","direction":"vertical","children":["name","on","send"]},
    {"id":"name","type":"text_input","label":"Name","bind":"name","max_length":100},
    {"id":"on","type":"toggle","label":"Enabled","bind":"on"},
    {"id":"send","type":"button","label":"Respond","action":{"type":"submit","action_id":"reply","include":["name"]}}]}
""".trimIndent()
internal val interactiveUiFallback = TimelineItemTextContent("Fallback text", null, "Fallback text", false)
internal fun interactiveUiContent(revision: Long = 0, threadRootId: ThreadId? = null): TimelineItemInteractiveUiContent {
    val json = Json.parseToJsonElement(interactiveUiPayloadJson.replace("\"revision\":0", "\"revision\":$revision"))
    val parsed = InteractiveUiParser.parse(buildJsonObject { put(InteractiveUiConstants.PAYLOAD_KEY, json) }) as InteractiveUiParseResult.Valid
    return TimelineItemInteractiveUiContent(
        EventId("$" + "ui"),
        UserId("@sender:test"),
        "Sender",
        threadRootId,
        interactiveUiFallback,
        InteractiveUiTimelineModel.Valid(parsed.payload, persistentMapOf(), persistentMapOf())
    )
}
internal fun interactiveUiEvent(payload: String? = interactiveUiPayloadJson): String = buildJsonObject {
    put("type", "m.room.message")
    put("content", buildJsonObject {
        put("msgtype", "m.text")
        put("body", "Fallback text")
        if (payload != null) put(InteractiveUiConstants.PAYLOAD_KEY, Json.parseToJsonElement(payload))
    })
}.toString()
internal class InMemoryInteractiveUiStateStore : InteractiveUiStateStore {
    val values = mutableMapOf<Pair<RoomId, EventId>, JsonObject>()
    override fun peek(roomId: RoomId, eventId: EventId): JsonElement? = values[roomId to eventId]
    override suspend fun load(roomId: RoomId, eventId: EventId): JsonElement? = peek(roomId, eventId)
    override suspend fun save(roomId: RoomId, eventId: EventId, envelope: JsonObject) {
        values[roomId to eventId] = envelope
    }
}
