/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.ui.media

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.media.MatrixMediaLoader
import io.element.android.libraries.matrix.api.media.MediaFile
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.robolectric.annotation.GraphicsMode
import java.io.IOException

@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EncryptedMediaCacheTest : RobolectricTest() {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `warm Coil reuses valid descriptor but changed hash key or IV misses and rejects`() = runTest {
        val valid = encryptedMediaSource()
        val loader = DescriptorCheckingLoader(valid)
        val coil = newImageLoader(loader)
        try {
            assertThat(coil.execute(request(valid))).isInstanceOf(SuccessResult::class.java)
            val cached = coil.execute(request(valid)) as SuccessResult
            assertThat(cached.dataSource).isEqualTo(DataSource.MEMORY_CACHE)
            assertThat(loader.downloads).isEqualTo(1)
            for ((original, replacement) in listOf(
                "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC" to "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" to "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
                "AAAAAAAAAAAAAAAAAAAAAA" to "BBBBBBBBBBBBBBBBBBBBBB",
            )) {
                val changed = valid.copy(json = valid.json!!.replace(original, replacement))
                assertThat(coil.execute(request(changed))).isInstanceOf(ErrorResult::class.java)
            }
            assertThat(loader.downloads).isEqualTo(4)
            assertThat(loader.closedFiles).isEqualTo(1)
            assertThat(loader.cachedReads).isEqualTo(0)
        } finally {
            coil.shutdown()
        }
    }

    @Test
    fun `cold Coil ignores warm URI only SDK cache and rejects changed descriptor`() = runTest {
        val valid = encryptedMediaSource()
        val loader = DescriptorCheckingLoader(valid)
        // Simulate a previously populated SDK cache returning bytes regardless of descriptor.
        assertThat(loader.loadMediaContent(valid).getOrThrow()).isNotEmpty()
        val coil = newImageLoader(loader)
        try {
            val changed = valid.copy(json = valid.json!!.replace("CCCCCCCC", "DDDDDDDD"))
            assertThat(coil.execute(request(changed))).isInstanceOf(ErrorResult::class.java)
            assertThat(loader.downloads).isEqualTo(1)
            assertThat(loader.cachedReads).isEqualTo(1)
        } finally {
            coil.shutdown()
        }
    }

    @Test
    fun `plain pixels at same URI cannot satisfy encrypted request and corrupt fresh bytes fail decoding`() = runTest {
        val valid = encryptedMediaSource()
        val loader = DescriptorCheckingLoader(valid)
        val coil = newImageLoader(loader)
        try {
            assertThat(coil.execute(request(MediaSource(valid.safeUrl)))).isInstanceOf(SuccessResult::class.java)
            loader.corruptDownload = true
            assertThat(coil.execute(request(valid))).isInstanceOf(ErrorResult::class.java)
            assertThat(loader.downloads).isEqualTo(2)
            assertThat(loader.closedFiles).isEqualTo(2)
        } finally {
            coil.shutdown()
        }
    }

    @Test
    fun `legacy decrypted plain content and thumbnail entries cannot decode after loader restart`() = runTest {
        val valid = encryptedMediaSource()
        val loader = DescriptorCheckingLoader(valid).apply { plainCiphertext = true }
        // Preseed both URI-only SDK cache paths with a decodable old image.
        loader.loadMediaContent(valid).getOrThrow()
        loader.loadMediaThumbnail(valid, 32, 64).getOrThrow()
        repeat(2) {
            val coil = newImageLoader(loader)
            try {
                for (kind in listOf(MediaRequestData.Kind.Content, MediaRequestData.Kind.Thumbnail(32, 64))) {
                    assertThat(coil.execute(request(MediaSource(valid.safeUrl), kind))).isInstanceOf(ErrorResult::class.java)
                }
                assertThat(coil.execute(request(valid))).isInstanceOf(SuccessResult::class.java)
            } finally {
                coil.shutdown()
            }
        }
        assertThat(loader.cachedReads).isEqualTo(2)
        assertThat(loader.downloads).isEqualTo(6)
        assertThat(loader.closedFiles).isEqualTo(6)
    }

    @Test
    fun `plain thumbnail miss decodes full content at Coil target size and reuses decoded memory`() = runTest {
        val loader = DescriptorCheckingLoader(encryptedMediaSource())
        val coil = newImageLoader(loader)
        try {
            val request = request(MediaSource("mxc://server/plain"), MediaRequestData.Kind.Thumbnail(1, 1))
            val result = coil.execute(request) as SuccessResult
            assertThat(result.image.width).isEqualTo(1)
            assertThat(result.image.height).isEqualTo(1)
            assertThat((coil.execute(request) as SuccessResult).dataSource).isEqualTo(DataSource.MEMORY_CACHE)
            assertThat(loader.downloads).isEqualTo(1)
            assertThat(loader.cachedReads).isEqualTo(0)
        } finally {
            coil.shutdown()
        }
    }

    private fun newImageLoader(loader: MatrixMediaLoader) = ImageLoader.Builder(ApplicationProvider.getApplicationContext())
        .components {
            add(MediaRequestDataKeyer())
            add(MediaRequestDataFetcherFactory(loader))
        }
        .build()

    private fun request(source: MediaSource, kind: MediaRequestData.Kind = MediaRequestData.Kind.Content) = ImageRequest.Builder(
        ApplicationProvider.getApplicationContext()
    )
        .data(MediaRequestData(source, kind))
        .size(if (kind is MediaRequestData.Kind.Thumbnail) 1 else 2)
        .build()

    private inner class DescriptorCheckingLoader(private val valid: MediaSource) : MatrixMediaLoader {
        var downloads = 0
        var cachedReads = 0
        var closedFiles = 0
        var corruptDownload = false
        var plainCiphertext = false
        private val image = temporaryFolder.newFile().apply {
            outputStream().use { Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.readBytes()

        override suspend fun loadMediaContent(source: MediaSource): Result<ByteArray> {
            cachedReads++
            return Result.success(image)
        }

        override suspend fun loadMediaThumbnail(source: MediaSource, width: Long, height: Long) = loadMediaContent(source)

        override suspend fun downloadMediaFile(source: MediaSource, mimeType: String?, filename: String?, useCache: Boolean): Result<MediaFile> {
            downloads++
            check(!useCache) { "Encrypted request must bypass URI cache" }
            if (source.isEncrypted && source != valid) return Result.failure(IOException("Descriptor verification failed"))
            val file = temporaryFolder.newFile().apply {
                writeBytes(if (corruptDownload ||
                plainCiphertext && !source.isEncrypted) {
                    byteArrayOf(1, 2, 3)
                } else {
                    image
                })
            }
            return Result.success(object : MediaFile {
                override fun path() = file.path
                override fun persist(path: String) = false
                override fun close() {
                    if (file.delete()) closedFiles++
                }
            })
        }
    }
}
