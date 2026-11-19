/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.fixtures.aMediaAttachment
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.item.TimelineItemDebugInfo
import io.element.android.libraries.mediaviewer.api.GalleryInfo
import io.element.android.libraries.mediaviewer.api.GalleryItemData
import io.element.android.libraries.mediaviewer.api.MediaViewerEntryPoint
import io.element.android.libraries.mediaviewer.api.anImageMediaInfo
import io.element.android.libraries.mediaviewer.test.viewer.aLocalMedia
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test

class MediaNavigationPrivacyTest : RobolectricTest() {
    private val eventId = EventId("\$safe-event")
    private val source = MediaSource("mxc://example.org/private-source", "private-source-descriptor")
    private val info = anImageMediaInfo(caption = "private-caption", senderName = "private-sender").copy(filename = "private-file.pdf")

    @Test
    fun `attachment preview navigation logging omits local media and its metadata`() {
        val target = MessagesFlowNode.NavTarget.AttachmentPreview(
            timelineMode = Timeline.Mode.Live,
            attachments = persistentListOf(aMediaAttachment(aLocalMedia(Uri.parse("content://fixture/private-uri"), info))),
            inReplyToEventId = eventId,
        )
        assertThat(target.toString()).doesNotContain("private-")
        assertThat(target.toString()).contains("attachmentCount=1")
        assertThat(target.toString()).contains(eventId.value)
    }

    @Test
    fun `media viewer navigation logging omits captions names and source descriptors`() {
        val target = MessagesFlowNode.NavTarget.MediaViewer(
            mode = MediaViewerEntryPoint.MediaViewerMode.EventGallery,
            eventId = eventId,
            mediaInfo = info,
            mediaSource = source,
            thumbnailSource = source,
            canUseOverlay = false,
            blurHash = "private-blurhash",
        )
        assertThat(target.toString()).doesNotContain("private-")
        assertThat(target.toString()).contains(eventId.value)
    }

    @Test
    fun `event source navigation logging omits raw event content`() {
        val target = MessagesFlowNode.NavTarget.EventDebugInfo(
            eventId,
            TimelineItemDebugInfo("private-model", "private-original-event", "private-edited-event"),
        )
        assertThat(target.toString()).doesNotContain("private-")
        assertThat(target.toString()).contains(eventId.value)
    }

    @Test
    fun `avatar navigation logging omits profile metadata`() {
        val target = MessagesFlowNode.NavTarget.AvatarPreview("private-profile", "mxc://example.org/private-avatar")
        assertThat(target.toString()).doesNotContain("private-")
        assertThat(target.toString()).contains("AvatarPreview")
    }

    @Test
    fun `gallery viewer navigation logging omits items and shared caption metadata`() {
        val target = MessagesFlowNode.NavTarget.GalleryViewer(
            eventId = eventId,
            galleryInfo = GalleryInfo("private-caption", null, null, "private-sender", null, null, null, 0),
            canUseOverlay = false,
            galleryItems = listOf(GalleryItemData("private-file.pdf", "application/pdf", source, source, GalleryItemData.Type.File, null)),
        )
        assertThat(target.toString()).doesNotContain("private-")
        assertThat(target.toString()).contains("itemCount=1")
        assertThat(target.toString()).contains(eventId.value)
    }
}
