/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.model.event

import io.element.android.libraries.mediaviewer.api.GalleryItemData

internal fun TimelineItemGalleryContent.toGalleryItemData(): List<GalleryItemData> = items.map { item ->
    GalleryItemData(
        filename = item.filename,
        mimeType = item.mimeType,
        mediaSource = item.mediaSource,
        thumbnailSource = item.thumbnailSource,
        type = item.type.toMediaViewerType(),
        blurHash = item.blurhash,
    )
}

internal fun TimelineItemAttachmentsContent.toGalleryItemData(): List<GalleryItemData> = attachments.map { item ->
    GalleryItemData(
        filename = item.filename,
        mimeType = item.mimeType,
        mediaSource = item.mediaSource,
        thumbnailSource = item.thumbnailSource,
        type = item.type.toMediaViewerType(),
        blurHash = null,
    )
}

internal fun GalleryItem.Type.toMediaViewerType(): GalleryItemData.Type = when (this) {
    GalleryItem.Type.Image -> GalleryItemData.Type.Image
    GalleryItem.Type.Video -> GalleryItemData.Type.Video
    GalleryItem.Type.Audio -> GalleryItemData.Type.Audio
    GalleryItem.Type.File -> GalleryItemData.Type.File
}
