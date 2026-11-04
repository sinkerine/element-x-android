/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class, ExperimentalCoilApi::class)

package io.element.android.libraries.interactiveui.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImagePainter
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import coil3.request.ErrorResult
import com.google.common.truth.Truth.assertThat
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.theme.Theme
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiRenderNode
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.ui.media.MediaRequestData
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.setSafeContent
import org.junit.Test

class InteractiveUiMediaTest : RobolectricTest() {
    @Test
    fun `protected encrypted source is never requested until revealed and load failure shows alt`() = runAndroidComposeUiTest {
        val requests = mutableListOf<MediaRequestData>()
        val handler = object : AsyncImagePreviewHandler {
            override suspend fun handle(imageLoader: coil3.ImageLoader, request: coil3.request.ImageRequest): AsyncImagePainter.State {
                requests.add(request.data as MediaRequestData)
                return AsyncImagePainter.State.Error(null, ErrorResult(null, request, IllegalStateException("Fixture media failure")))
            }
        }
        val source = MediaSource("mxc://example.org/encrypted", "{\"file\":\"preserved-encrypted-fixture\"}")
        val image = InteractiveUiRenderNode.Image("image", "Encrypted photograph unavailable", source, "image/png", 1.5f, true)
        val model = mutableStateOf(image)
        setSafeContent {
            ElementTheme(Theme.Light) {
                CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides handler) {
                    InteractiveUiView(viewState(model.value), {}, fallback = {})
                }
            }
        }
        onNodeWithText("Encrypted photograph unavailable").assertIsDisplayed()
        assertThat(requests).isEmpty()
        runOnIdle { model.value = model.value.copy(hidden = false) }
        waitForIdle()
        onNodeWithText("Encrypted photograph unavailable").assertIsDisplayed()
        assertThat(requests).containsExactly(MediaRequestData(source, MediaRequestData.Kind.Content))
    }
}
