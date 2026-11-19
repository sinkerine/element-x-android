/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.model.event

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.timeline.components.event.aGalleryItem
import io.element.android.features.messages.impl.timeline.components.event.aTimelineItemGalleryContent
import io.element.android.libraries.mediaviewer.api.GalleryItemData
import kotlinx.collections.immutable.toImmutableList
import org.junit.Test

class GalleryItemDataMapperTest {
    private val items = GalleryItem.Type.entries.mapIndexed { index, type -> aGalleryItem(filename = "item$index", type = type) }

    @Test
    fun `gallery content maps every item with its type and order`() {
        val mapped = aTimelineItemGalleryContent(items = items).toGalleryItemData()
        assertThat(mapped.map { it.filename }).containsExactlyElementsIn(items.map { it.filename }).inOrder()
        assertThat(mapped.map { it.type }).containsExactly(
            GalleryItemData.Type.Image,
            GalleryItemData.Type.Video,
            GalleryItemData.Type.Audio,
            GalleryItemData.Type.File,
        ).inOrder()
        mapped.zip(items).forEach { (actual, original) ->
            assertThat(actual.mediaSource).isEqualTo(original.mediaSource)
            assertThat(actual.thumbnailSource).isEqualTo(original.thumbnailSource)
            assertThat(actual.mimeType).isEqualTo(original.mimeType)
            assertThat(actual.blurHash).isEqualTo(original.blurhash)
        }
    }

    @Test
    fun `attachments content maps each item with its real type, not File`() {
        val content = TimelineItemAttachmentsContent(
            body = "",
            caption = null,
            formattedCaption = null,
            isEdited = false,
            attachments = items.map {
                AttachmentItem(it.filename, it.mimeType, it.mediaSource, it.thumbnailSource, null, "", "", it.type)
            }.toImmutableList(),
        )
        val mapped = content.toGalleryItemData()
        assertThat(mapped.map { it.filename }).containsExactlyElementsIn(items.map { it.filename }).inOrder()
        assertThat(mapped.map { it.type }).containsExactly(
            GalleryItemData.Type.Image,
            GalleryItemData.Type.Video,
            GalleryItemData.Type.Audio,
            GalleryItemData.Type.File,
        ).inOrder()
    }
}
