/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.factories.event

import dev.zacsweers.metro.Inject
import io.element.android.features.messages.impl.timeline.model.event.InteractiveUiTimelineModel
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemInteractiveUiContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemTextContent
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.htmlrenderer.api.HtmlMessageParser
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiImage
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiNode
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiParseResult
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiParser
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.permalink.PermalinkParser
import io.element.android.libraries.matrix.api.timeline.item.EventThreadInfo
import io.element.android.libraries.matrix.api.timeline.item.event.EventTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.FormattedBody
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.MessageFormat
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.getDisambiguatedDisplayName
import io.element.android.libraries.matrix.ui.messages.toHtmlDocument
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

@Inject
class TimelineItemContentInteractiveUiFactory(
    private val featureFlagService: FeatureFlagService,
    private val permalinkParser: PermalinkParser,
    private val htmlMessageParser: HtmlMessageParser,
    private val dispatchers: CoroutineDispatchers,
) {
    suspend fun create(eventTimelineItem: EventTimelineItem, fallback: TimelineItemTextContent): TimelineItemInteractiveUiContent? {
        val eventId = eventTimelineItem.eventId ?: return null
        if (!eventTimelineItem.isRemote ||
            (eventTimelineItem.content as? MessageContent)?.type !is TextMessageType ||
            !featureFlagService.isFeatureEnabled(FeatureFlags.InteractiveUi)) {
                return null
            }
        return withContext(dispatchers.computation) {
            runCatchingExceptions {
                val info = eventTimelineItem.timelineItemDebugInfoProvider()
                val model = when (val result = InteractiveUiParser.parseEvent(info.originalJson, info.latestEditedJson)) {
                    null -> return@runCatchingExceptions null
                    is InteractiveUiParseResult.Invalid -> InteractiveUiTimelineModel.Invalid(result.error.code)
                    is InteractiveUiParseResult.Valid -> {
                        val richText = result.payload.nodes.filterIsInstance<InteractiveUiNode.RichText>().mapNotNull { node ->
                            node.html?.let { FormattedBody(MessageFormat.HTML, it).toHtmlDocument(permalinkParser) }?.let { document ->
                                document.select("a, details, summary").unwrap()
                                node.id to htmlMessageParser.parse(document)
                            }
                        }.toMap().toImmutableMap()
                        val media = buildMap {
                            result.payload.nodes.forEach { node ->
                                when (node) {
                                is InteractiveUiNode.Image -> put(node.id, node.image.mediaSource())
                                is InteractiveUiNode.Carousel -> node.items.forEach { put("${node.id}/${it.id}", it.image.mediaSource()) }
                                else -> Unit
                            }
                            }
                        }.toImmutableMap()
                        InteractiveUiTimelineModel.Valid(result.payload, richText, media)
                    }
                }
                TimelineItemInteractiveUiContent(
                    eventId,
                    eventTimelineItem.sender,
                    eventTimelineItem.senderProfile.getDisambiguatedDisplayName(eventTimelineItem.sender),
                    (eventTimelineItem.threadInfo() as? EventThreadInfo.ThreadResponse)?.threadRootId,
                    fallback,
                    model
                )
            }.getOrNull()
        }
    }
}

/** Rust MediaSource's serde representation wraps encrypted media under the file key. */
private fun InteractiveUiImage.mediaSource(): MediaSource {
    val file = encryptedFile
    return if (file != null) {
        MediaSource(file.getValue("url").jsonPrimitive.content, buildJsonObject { put("file", file) }.toString())
    } else {
        MediaSource(requireNotNull(mxcUrl))
    }
}
