/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.share.api

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Share intent data, mapped from the original [android.content.Intent].
 */
sealed interface ShareIntentData : Parcelable {
    /**
     * A list of [Uri]s to share and their mime types, with an optional [text] to be used as caption.
     */
    @Parcelize
    data class Uris(val text: String?, val uris: List<UriToShare>) : ShareIntentData {
        override fun toString(): String = "ShareIntentData.Uris(uriCount=${uris.size})"
    }

    /**
     * A plain text to share.
     */
    @Parcelize
    data class PlainText(val content: String) : ShareIntentData {
        override fun toString(): String = "ShareIntentData.PlainText"
    }
}

/**
 * A [Uri] coming from an external share intent, with its associated [mimeType].
 */
@Parcelize
data class UriToShare(
    val uri: Uri,
    val mimeType: String,
) : Parcelable {
    override fun toString(): String = "UriToShare"
}
