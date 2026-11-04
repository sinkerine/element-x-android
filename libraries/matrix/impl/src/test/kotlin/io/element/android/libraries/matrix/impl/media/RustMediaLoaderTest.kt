/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.media

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiMediaSource
import io.element.android.tests.testutils.testCoroutineDispatchers
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.MediaFileHandle
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import org.matrix.rustcomponents.sdk.MediaSource as RustMediaSource

class RustMediaLoaderTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val source = MediaSource("mxc://server/image", """{"file":{}}""")
    private val rustSource = FakeFfiMediaSource()
    private val client = mockk<Client>()

    @Before
    fun setUp() {
        mockkObject(RustMediaSource.Companion)
        every { RustMediaSource.fromJson(source.json!!) } returns rustSource
    }

    @After
    fun tearDown() {
        unmockkObject(RustMediaSource.Companion)
    }

    @Test
    fun `uncached file download forwards flag and transfers handle ownership to caller`() = runTest {
        val handle = mockk<MediaFileHandle>(relaxed = true)
        val expectedPath = temporaryFolder.newFile().path
        every { handle.path() } returns expectedPath
        coEvery { client.getMediaFile(rustSource, null, "image/png", false, any()) } returns handle
        val loader = RustMediaLoader(temporaryFolder.root, testCoroutineDispatchers(), client)

        val file = loader.downloadMediaFile(source, "image/png", null, useCache = false).getOrThrow()

        assertThat(file.path()).isEqualTo(expectedPath)
        verify(exactly = 0) { handle.close() }
        file.close()
        verify(exactly = 1) { handle.close() }
        coVerify(exactly = 1) {
            client.getMediaFile(rustSource, null, "image/png", false, "${temporaryFolder.root.path}/temp/media")
        }
        coVerify(exactly = 0) { client.getMediaContent(any()) }
    }

    @Test
    fun `SDK verification errors remain failed results`() = runTest {
        val failure = IOException("Verification failed")
        coEvery { client.getMediaFile(any(), any(), any(), false, any()) } throws failure
        val loader = RustMediaLoader(temporaryFolder.root, testCoroutineDispatchers(), client)

        assertThat(loader.downloadMediaFile(source, null, null, false).exceptionOrNull()).isSameInstanceAs(failure)
    }

    @Test
    fun `cancelled dispatcher return closes downloaded handle`() = runTest {
        val handle = mockk<MediaFileHandle>(relaxed = true)
        val loader = RustMediaLoader(temporaryFolder.root, testCoroutineDispatchers(), client)
        val job = launch {
            coEvery { client.getMediaFile(any(), any(), any(), false, any()) } coAnswers {
                cancel()
                handle
            }
            loader.downloadMediaFile(source, null, null, false)
            error("Cancelled download returned")
        }
        job.join()

        assertThat(job.isCancelled).isTrue()
        verify(exactly = 1) { handle.close() }
    }

    @Test
    fun `caller cancellation propagates to SDK download`() = runTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        coEvery { client.getMediaFile(any(), any(), any(), false, any()) } coAnswers {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val loader = RustMediaLoader(temporaryFolder.root, testCoroutineDispatchers(), client)
        val job = launch { loader.downloadMediaFile(source, null, null, false) }
        started.await()
        job.cancel()
        job.join()

        assertThat(cancelled.isCompleted).isTrue()
    }

    @Test
    fun `SDK cancellation is rethrown`() = runTest {
        val failure = CancellationException("Cancelled")
        coEvery { client.getMediaFile(any(), any(), any(), false, any()) } throws failure
        val loader = RustMediaLoader(temporaryFolder.root, testCoroutineDispatchers(), client)

        val thrown = captureFailure { loader.downloadMediaFile(source, null, null, false) }
        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        assertThat(thrown).hasMessageThat().isEqualTo(failure.message)
    }
}

private suspend fun captureFailure(block: suspend () -> Unit): Throwable? = try {
    block()
    null
} catch (error: Exception) {
    error
}
