/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.ui.media

import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import io.element.android.libraries.matrix.api.media.MatrixMediaLoader
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.media.toFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import timber.log.Timber

internal class CoilMediaFetcher(
    private val mediaLoader: MatrixMediaLoader,
    private val mediaData: MediaRequestData,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val mediaSource = mediaData.source
        if (mediaSource == null) {
            Timber.e("MediaData source is null")
            return null
        }
        // Older SDK entries can contain decrypted bytes under URI-only content and thumbnail keys.
        // The SDK exposes no scoped eviction or uncached thumbnail API. Coil still reuses decoded
        // images by descriptor and request kind; on a miss, download without reading/writing the SDK cache.
        val fileKind = mediaData.kind as? MediaRequestData.Kind.File
        return fetchFile(mediaSource, fileKind?.mimeType, fileKind?.fileName)
    }

    /**
     * This method is here to avoid using [MatrixMediaLoader.loadMediaContent] as too many ByteArray allocations will flood the memory and cause lots of GC.
     * The MediaFile will be closed (and so destroyed from disk) when the image source is closed.
     *
     */
    private suspend fun fetchFile(
        mediaSource: MediaSource,
        mimeType: String?,
        fileName: String?,
    ): FetchResult {
        val mediaFile = mediaLoader.downloadMediaFile(mediaSource, mimeType, fileName, useCache = false).getOrThrow()
        try {
            currentCoroutineContext().ensureActive()
            return SourceFetchResult(
                source = ImageSource(
                    file = mediaFile.toFile().toOkioPath(),
                    fileSystem = FileSystem.SYSTEM,
                    closeable = mediaFile,
                ),
                mimeType = null,
                dataSource = DataSource.DISK
            )
        } catch (error: Throwable) {
            mediaFile.close()
            throw error
        }
    }
}
