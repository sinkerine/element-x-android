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
import io.element.android.libraries.matrix.api.exception.isNetworkError
import io.element.android.libraries.matrix.api.media.MatrixMediaLoader
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.media.toFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import timber.log.Timber
import java.nio.ByteBuffer

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
        if (mediaSource.isEncrypted) {
            // The SDK content/thumbnail cache uses only the URI and can bypass descriptor verification.
            val fileKind = mediaData.kind as? MediaRequestData.Kind.File
            return fetchFile(mediaSource, fileKind?.mimeType, fileKind?.fileName, useCache = false)
        }
        return when (val kind = mediaData.kind) {
            is MediaRequestData.Kind.Content -> fetchContent(mediaSource)
            is MediaRequestData.Kind.Thumbnail -> {
                fetchThumbnail(mediaSource, kind).fold(
                    onSuccess = { it },
                    onFailure = { error ->
                        if (error.isNetworkError()) {
                            throw error
                        } else {
                            fetchContent(mediaSource)
                        }
                    }
                )
            }
            is MediaRequestData.Kind.File -> fetchFile(mediaSource, kind.mimeType, kind.fileName)
        }
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
        useCache: Boolean = true,
    ): FetchResult {
        val mediaFile = mediaLoader.downloadMediaFile(mediaSource, mimeType, fileName, useCache).getOrThrow()
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

    private suspend fun fetchContent(mediaSource: MediaSource): FetchResult? {
        return mediaLoader.loadMediaContent(
            source = mediaSource,
        ).map { byteArray ->
            byteArray.asSourceResult()
        }.onFailure {
            Timber.e(it)
        }.getOrThrow()
    }

    private suspend fun fetchThumbnail(mediaSource: MediaSource, kind: MediaRequestData.Kind.Thumbnail): Result<FetchResult> {
        return mediaLoader.loadMediaThumbnail(
            source = mediaSource,
            width = kind.width,
            height = kind.height,
        ).map { byteArray ->
            byteArray.asSourceResult()
        }.onFailure {
            Timber.e(it)
        }
    }

    private fun ByteArray.asSourceResult(): SourceFetchResult {
        val byteBuffer = ByteBuffer.wrap(this)
        val bufferedSource = try {
            Buffer().apply { write(byteBuffer) }
        } finally {
            byteBuffer.position(0)
        }
        return SourceFetchResult(
            source = ImageSource(
                source = bufferedSource,
                fileSystem = FileSystem.SYSTEM,
            ),
            mimeType = null,
            dataSource = DataSource.MEMORY
        )
    }
}
