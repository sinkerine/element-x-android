/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.notifications

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.matrix.api.media.MatrixMediaLoader
import io.element.android.libraries.matrix.api.media.MediaFile
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.ui.media.DefaultImageLoaderFactory
import io.element.android.libraries.matrix.ui.media.test.FakeInitialsAvatarBitmapGenerator
import io.element.android.services.toolbox.test.sdk.FakeBuildVersionSdkIntProvider
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DefaultNotificationBitmapLoaderTest : RobolectricTest() {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `large uncached avatar decodes and circle crops to notification target size`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val avatarFile = temporaryFolder.newFile().apply {
            val bitmap = Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.RED)
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        var downloads = 0
        var closes = 0
        val mediaLoader = object : MatrixMediaLoader {
            override suspend fun loadMediaContent(source: MediaSource): Result<ByteArray> = error("Unexpected SDK cache read")
            override suspend fun loadMediaThumbnail(source: MediaSource, width: Long, height: Long): Result<ByteArray> =
                error("Unexpected SDK thumbnail cache read")

            override suspend fun downloadMediaFile(source: MediaSource, mimeType: String?, filename: String?, useCache: Boolean): Result<MediaFile> {
                assertThat(useCache).isFalse()
                downloads++
                val downloaded = temporaryFolder.newFile().apply { writeBytes(avatarFile.readBytes()) }
                return Result.success(object : MediaFile {
                    override fun path() = downloaded.path
                    override fun persist(path: String) = false
                    override fun close() {
                        if (downloaded.delete()) closes++
                    }
                })
            }
        }
        val imageLoader = DefaultImageLoaderFactory(context, { OkHttpClient() }).newImageLoader(mediaLoader)
        try {
            val sut = DefaultNotificationBitmapLoader(context, FakeBuildVersionSdkIntProvider(35), FakeInitialsAvatarBitmapGenerator())
            val result = sut.getRoomBitmap(
                avatarData = AvatarData("!room:server", "Room", "mxc://server/large-avatar", AvatarSize.RoomListItem),
                imageLoader = imageLoader,
                targetSize = 64,
            )
            assertThat(result).isNotNull()
            result!!
            assertThat(result.width).isEqualTo(64)
            assertThat(result.height).isEqualTo(64)
            assertThat(result.allocationByteCount).isAtMost(64 * 64 * 4)
            assertThat(Color.alpha(result.getPixel(0, 0))).isEqualTo(0)
            assertThat(result.getPixel(32, 32)).isEqualTo(Color.RED)
            assertThat(downloads).isEqualTo(1)
            assertThat(closes).isEqualTo(1)
        } finally {
            imageLoader.shutdown()
        }
    }
}
