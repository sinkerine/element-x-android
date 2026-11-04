/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.ui.media

import coil3.fetch.SourceFetchResult
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.media.MatrixMediaLoader
import io.element.android.libraries.matrix.api.media.MediaFile
import io.element.android.libraries.matrix.api.media.MediaSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class CoilMediaFetcherTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `encrypted content thumbnails and files bypass SDK caches and close files on source disposal`() = runTest {
        val source = encryptedMediaSource()
        val loader = mockk<MatrixMediaLoader>()
        for (kind in listOf(
            MediaRequestData.Kind.Content,
            MediaRequestData.Kind.Thumbnail(32, 64),
            MediaRequestData.Kind.File("image.png", "image/png"),
        )) {
            val file = temporaryFolder.newFile()
            val handle = mockk<MediaFile>(relaxed = true)
            every { handle.path() } returns file.path
            coEvery { loader.downloadMediaFile(source, any(), any(), false) } returns Result.success(handle)
            val result = CoilMediaFetcher(loader, MediaRequestData(source, kind)).fetch() as SourceFetchResult
            verify(exactly = 0) { handle.close() }
            result.source.close()
            verify(exactly = 1) { handle.close() }
            val fileKind = kind as? MediaRequestData.Kind.File
            coVerify { loader.downloadMediaFile(source, fileKind?.mimeType, fileKind?.fileName, false) }
        }
        coVerify(exactly = 0) { loader.loadMediaContent(any()) }
        coVerify(exactly = 0) { loader.loadMediaThumbnail(any(), any(), any()) }
    }

    @Test
    fun `file is closed if source creation fails`() = runTest {
        val handle = mockk<MediaFile>(relaxed = true)
        val failure = IOException("Missing temporary file")
        every { handle.path() } throws failure
        val loader = mockk<MatrixMediaLoader>()
        coEvery { loader.downloadMediaFile(any(), any(), any(), false) } returns Result.success(handle)
        val thrown = captureFailure { CoilMediaFetcher(loader, encryptedRequest()).fetch() }
        assertThat(thrown).isSameInstanceAs(failure)
        verify(exactly = 1) { handle.close() }
    }

    @Test
    fun `file is closed when cancelled before ownership transfers to Coil`() = runTest {
        val handle = mockk<MediaFile>(relaxed = true)
        val loader = mockk<MatrixMediaLoader>()
        val job = launch {
            coEvery { loader.downloadMediaFile(any(), any(), any(), false) } coAnswers {
                cancel()
                Result.success(handle)
            }
            CoilMediaFetcher(loader, encryptedRequest()).fetch()
            error("Cancelled fetch returned")
        }
        job.join()
        assertThat(job.isCancelled).isTrue()
        verify(exactly = 1) { handle.close() }
    }

    @Test
    fun `cancellation propagates to in-flight download`() = runTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val loader = mockk<MatrixMediaLoader>()
        coEvery { loader.downloadMediaFile(any(), any(), any(), false) } coAnswers {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val job = launch { CoilMediaFetcher(loader, encryptedRequest()).fetch() }
        started.await()
        job.cancel()
        job.join()
        assertThat(cancelled.isCompleted).isTrue()
    }

    @Test
    fun `encrypted verification failure and cancellation never fall back to cached content`() = runTest {
        val loader = mockk<MatrixMediaLoader>()
        for (failure in listOf(IOException("Invalid descriptor"), CancellationException("Cancelled"))) {
            coEvery { loader.downloadMediaFile(any(), any(), any(), false) } returns Result.failure(failure)
            val request = encryptedRequest().copy(kind = MediaRequestData.Kind.Thumbnail(32))
            val thrown = captureFailure { CoilMediaFetcher(loader, request).fetch() }
            assertThat(thrown).isSameInstanceAs(failure)
        }
        coVerify(exactly = 0) { loader.loadMediaContent(any()) }
        coVerify(exactly = 0) { loader.loadMediaThumbnail(any(), any(), any()) }
    }

    @Test
    fun `plain misses bypass preseeded legacy decrypted content and thumbnail caches after restart`() = runTest {
        val ciphertext = byteArrayOf(9, 8, 7)
        val decrypted = byteArrayOf(1, 2, 3)
        val source = MediaSource("mxc://server/image", """{"url":"mxc://server/image"}""")
        // The same persistent cache is retained when the loader and fetcher are recreated.
        val legacyCache = mutableMapOf(source.safeUrl to decrypted)
        repeat(2) {
            val loader = mockk<MatrixMediaLoader>()
            coEvery { loader.loadMediaContent(source) } answers { Result.success(legacyCache.getValue(source.safeUrl)) }
            coEvery { loader.loadMediaThumbnail(source, any(), any()) } answers { Result.success(legacyCache.getValue(source.safeUrl)) }
            coEvery { loader.downloadMediaFile(source, any(), any(), any()) } answers {
                val bytes = if (arg<Boolean>(3)) legacyCache.getValue(source.safeUrl) else ciphertext
                val file = temporaryFolder.newFile().apply { writeBytes(bytes) }
                val handle = mockk<MediaFile>(relaxed = true)
                every { handle.path() } returns file.path
                Result.success(handle)
            }
            for (kind in listOf(
                MediaRequestData.Kind.Content,
                MediaRequestData.Kind.Thumbnail(32, 64),
                MediaRequestData.Kind.File("image.png", "image/png"),
            )) {
                val result = CoilMediaFetcher(loader, MediaRequestData(source, kind)).fetch() as SourceFetchResult
                assertThat(result.source.source().readByteArray()).isEqualTo(ciphertext)
                result.source.close()
                val fileKind = kind as? MediaRequestData.Kind.File
                coVerify { loader.downloadMediaFile(source, fileKind?.mimeType, fileKind?.fileName, false) }
            }
            coVerify(exactly = 0) { loader.loadMediaContent(any()) }
            coVerify(exactly = 0) { loader.loadMediaThumbnail(any(), any(), any()) }
            assertThat(legacyCache.getValue(source.safeUrl)).isEqualTo(decrypted)
        }
    }

    @Test
    fun `fresh plain encrypted plain sequence never writes decrypted bytes into URI cache`() = runTest {
        val encrypted = encryptedMediaSource()
        val plain = MediaSource(encrypted.safeUrl, null)
        val ciphertext = byteArrayOf(9, 8, 7)
        val decrypted = byteArrayOf(1, 2, 3)
        val cache = mutableMapOf<String, ByteArray>()
        val loader = mockk<MatrixMediaLoader>()
        coEvery { loader.loadMediaContent(any()) } answers { Result.success(cache.getOrPut(plain.safeUrl) { ciphertext }) }
        coEvery { loader.downloadMediaFile(any(), any(), any(), any()) } answers {
            val source = arg<MediaSource>(0)
            val bytes = if (source.isEncrypted) decrypted else ciphertext
            if (arg<Boolean>(3)) cache[source.safeUrl] = bytes
            val file = temporaryFolder.newFile().apply { writeBytes(bytes) }
            val handle = mockk<MediaFile>(relaxed = true)
            every { handle.path() } returns file.path
            Result.success(handle)
        }
        for (source in listOf(plain, encrypted, plain, encrypted)) {
            val result = CoilMediaFetcher(loader, MediaRequestData(source, MediaRequestData.Kind.Content)).fetch() as SourceFetchResult
            assertThat(result.source.source().readByteArray()).isEqualTo(if (source.isEncrypted) decrypted else ciphertext)
            result.source.close()
        }
        assertThat(cache).isEmpty()
    }

    private fun encryptedRequest() = MediaRequestData(encryptedMediaSource(), MediaRequestData.Kind.Content)
}

private suspend fun captureFailure(block: suspend () -> Unit): Throwable? = try {
    block()
    null
} catch (error: Exception) {
    error
}
