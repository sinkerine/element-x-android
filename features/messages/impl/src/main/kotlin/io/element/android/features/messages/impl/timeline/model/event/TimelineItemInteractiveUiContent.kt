/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.model.event

import io.element.android.libraries.htmlrenderer.api.DocumentNode
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiErrorCode
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiPayload
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.media.MediaSource
import kotlinx.collections.immutable.ImmutableMap
import org.jsoup.nodes.Document

sealed interface InteractiveUiTimelineModel {
    data class Valid(val payload: InteractiveUiPayload, val richText: ImmutableMap<String, DocumentNode>,
        val media: ImmutableMap<String, MediaSource>) : InteractiveUiTimelineModel
    data class Invalid(val code: InteractiveUiErrorCode) : InteractiveUiTimelineModel {
        val showCaption: Boolean get() = code != InteractiveUiErrorCode.BadVersion
    }
}

data class TimelineItemInteractiveUiContent(
    val eventId: EventId,
    val senderId: UserId,
    val senderDisplayName: String,
    val threadRootId: ThreadId?,
    val fallback: TimelineItemTextContent,
    val model: InteractiveUiTimelineModel,
) : TimelineItemTextBasedContent {
    override val type: String = "TimelineItemInteractiveUiContent"
    override val body: String get() = fallback.body
    override val htmlDocument: Document? get() = fallback.htmlDocument
    override val messageTree: DocumentNode? get() = fallback.messageTree
    override val formattedBody: CharSequence get() = fallback.formattedBody
    override val plainText: String get() = fallback.plainText
    override val isEdited: Boolean get() = fallback.isEdited
}
