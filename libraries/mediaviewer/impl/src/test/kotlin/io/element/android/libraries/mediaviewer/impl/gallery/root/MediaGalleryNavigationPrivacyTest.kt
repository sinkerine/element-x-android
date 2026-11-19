/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.gallery.root

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.mediaviewer.api.MediaViewerEntryPoint
import io.element.android.libraries.mediaviewer.api.anImageMediaInfo
import org.junit.Test

class MediaGalleryNavigationPrivacyTest {
    @Test
    fun `viewer navigation diagnostics omit media content and sources`() {
        val eventId = EventId("\$event")
        val source = MediaSource("mxc://example.org/private-source", "private-encrypted-descriptor")
        val target = MediaGalleryFlowNode.NavTarget.MediaViewer(
            mode = MediaViewerEntryPoint.MediaViewerMode.EventGallery(false),
            eventId = eventId,
            mediaInfo = anImageMediaInfo(caption = "private-caption"),
            mediaSource = source,
            thumbnailSource = source,
            blurHash = "private-blur-hash",
        )
        assertThat(target.toString()).isEqualTo("MediaViewer(eventId=$eventId)")
    }
}
