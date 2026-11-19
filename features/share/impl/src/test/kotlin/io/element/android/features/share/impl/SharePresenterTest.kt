/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.share.impl

import android.net.Uri
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.features.share.api.OnSharedData
import io.element.android.features.share.api.ShareIntentData
import io.element.android.features.share.api.UriToShare
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.media.FileInfo
import io.element.android.libraries.matrix.test.A_MESSAGE
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.room.FakeBaseRoom
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfigProvider
import io.element.android.libraries.mediaupload.api.MediaSenderRoomFactory
import io.element.android.libraries.mediaupload.api.MediaUploadInfo
import io.element.android.libraries.mediaupload.test.FakeMediaOptimizationConfigProvider
import io.element.android.libraries.mediaupload.test.FakeMediaSender
import io.element.android.services.appnavstate.api.ActiveRoomsHolder
import io.element.android.services.appnavstate.impl.DefaultActiveRoomsHolder
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SharePresenterTest : RobolectricTest() {
    @get:Rule
    val warmUpRule = WarmUpRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val galleryInfo = MediaUploadInfo.AnyFile(java.io.File("report.pdf"), FileInfo("application/pdf", 123, null, null))

    @Test
    fun `share intent diagnostics omit content and attachment URIs`() {
        val item = UriToShare(Uri.parse("content://private-provider/private-file"), "application/pdf")
        val uris = ShareIntentData.Uris("private-caption", listOf(item))
        assertThat(uris.toString()).isEqualTo("ShareIntentData.Uris(uriCount=1)")
        assertThat(item.toString()).isEqualTo("UriToShare")
        assertThat(ShareIntentData.PlainText("private-text").toString()).isEqualTo("ShareIntentData.PlainText")
    }

    @Test
    fun `present - share several files sends one gallery per room with the text caption once`() = runTest {
        val rooms = listOf(A_ROOM_ID, RoomId("!other:test"))
        val client = FakeMatrixClient().apply { rooms.forEach { givenGetRoomResult(it, FakeJoinedRoom(baseRoom = FakeBaseRoom(roomId = it))) } }
        val processed = mutableListOf<Uri>()
        val galleries = mutableListOf<List<MediaUploadInfo>>()
        val captions = mutableListOf<String?>()
        var singleSends = 0
        var cleanup = 0
        val sender = FakeMediaSender(
            preProcessMediaLambda = { uri, _ ->
                processed.add(uri)
                val file = temporaryFolder.newFolder().resolve(uri.lastPathSegment!!).apply { writeText("fixture") }
                Result.success(galleryInfo.copy(file = file))
            },
            sendMediaResult = {
                singleSends++
                Result.success(Unit)
            },
            sendGalleryLambda = { infos, caption ->
                galleries.add(infos)
                captions.add(caption)
                Result.success(Unit)
            },
            cleanUpResult = { cleanup++ },
        )
        val files = List(3) { UriToShare(Uri.parse("content://files/$it.pdf"), "application/pdf") }
        val presenter = createSharePresenter(
            matrixClient = client,
            shareIntentData = ShareIntentData.Uris(A_MESSAGE, files),
            mediaSenderRoomFactory = MediaSenderRoomFactory { sender },
        )
        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem()
            presenter.onRoomSelected(rooms)
            assertThat(awaitItem().shareAction.isLoading()).isTrue()
            assertThat(awaitItem().shareAction.isSuccess()).isTrue()
            assertThat(galleries).hasSize(2)
            assertThat(singleSends).isEqualTo(0)
            assertThat(captions).containsExactly(A_MESSAGE, A_MESSAGE)
            galleries.forEach { infos ->
                assertThat(infos.map { it.file.name }).containsExactly("0.pdf", "1.pdf", "2.pdf").inOrder()
                assertThat(infos.none { it.file.exists() }).isTrue()
            }
            assertThat(processed).containsExactlyElementsIn(files.map { it.uri } + files.map { it.uri }).inOrder()
            assertThat(cleanup).isEqualTo(2)
        }
    }

    @Test
    fun `present - share more than 60 files keeps separate sends`() = runTest {
        var sends = 0
        var gallerySends = 0
        val sender = FakeMediaSender(
            sendMediaResult = {
                sends++
                Result.success(Unit)
            },
            sendGalleryLambda = { _, _ ->
                gallerySends++
                Result.success(Unit)
            },
        )
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, FakeJoinedRoom()) }
        val presenter = createSharePresenter(
            matrixClient = client,
            shareIntentData = ShareIntentData.Uris(A_MESSAGE, List(61) { UriToShare(Uri.parse("content://files/$it"), "text/plain") }),
            mediaSenderRoomFactory = MediaSenderRoomFactory { sender },
        )
        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem()
            presenter.onRoomSelected(listOf(A_ROOM_ID))
            assertThat(awaitItem().shareAction.isLoading()).isTrue()
            assertThat(awaitItem().shareAction.isSuccess()).isTrue()
            assertThat(sends).isEqualTo(61)
            assertThat(gallerySends).isEqualTo(0)
        }
    }

    @Test
    fun `present - share gallery preprocessing failure sends nothing and reports failure`() = runTest {
        val preparedFile = temporaryFolder.newFile()
        var processed = 0
        var singleSends = 0
        var gallerySends = 0
        var cleanup = 0
        val sender = FakeMediaSender(
            preProcessMediaLambda = { _, _ ->
                if (++processed == 2) {
                    Result.failure(IllegalStateException("synthetic"))
                } else {
                    Result.success(galleryInfo.copy(file = preparedFile))
                }
            },
            sendMediaResult = {
                singleSends++
                Result.success(Unit)
            },
            sendGalleryLambda = { _, _ ->
                gallerySends++
                Result.success(Unit)
            },
            cleanUpResult = { cleanup++ },
        )
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, FakeJoinedRoom()) }
        val presenter = createSharePresenter(
            matrixClient = client,
            shareIntentData = ShareIntentData.Uris(null, List(3) { UriToShare(Uri.parse("content://files/$it"), "text/plain") }),
            mediaSenderRoomFactory = MediaSenderRoomFactory { sender },
        )
        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem()
            presenter.onRoomSelected(listOf(A_ROOM_ID))
            assertThat(awaitItem().shareAction.isLoading()).isTrue()
            assertThat(awaitItem().shareAction.isFailure()).isTrue()
            assertThat(processed).isEqualTo(2)
            assertThat(preparedFile.exists()).isFalse()
            assertThat(singleSends).isEqualTo(0)
            assertThat(gallerySends).isEqualTo(0)
            assertThat(cleanup).isEqualTo(1)
        }
    }

    @Test
    fun `present - share gallery cancellation cleans up and rethrows`() = runTest {
        var singleSends = 0
        var gallerySends = 0
        var cleanup = 0
        var shared = false
        val preparedFile = temporaryFolder.newFile()
        var processed = 0
        val sender = FakeMediaSender(
            preProcessMediaLambda = { _, _ ->
                if (++processed == 2) {
                    Result.failure(kotlinx.coroutines.CancellationException("synthetic cancellation"))
                } else {
                    Result.success(galleryInfo.copy(file = preparedFile))
                }
            },
            sendMediaResult = {
                singleSends++
                Result.success(Unit)
            },
            sendGalleryLambda = { _, _ ->
                gallerySends++
                Result.success(Unit)
            },
            cleanUpResult = { cleanup++ },
        )
        val room = FakeJoinedRoom()
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val presenter = createSharePresenter(
            matrixClient = client,
            shareIntentData = ShareIntentData.Uris(null, List(2) { UriToShare(Uri.parse("content://files/$it"), "text/plain") }),
            mediaSenderRoomFactory = MediaSenderRoomFactory { sender },
            onSharedData = OnSharedData { shared = true },
        )
        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem()
            presenter.onRoomSelected(listOf(A_ROOM_ID))
            assertThat(awaitItem().shareAction.isLoading()).isTrue()
            advanceUntilIdle()
            assertThat(singleSends).isEqualTo(0)
            assertThat(gallerySends).isEqualTo(0)
            assertThat(cleanup).isEqualTo(1)
            assertThat(preparedFile.exists()).isFalse()
            assertThat(shared).isFalse()
            room.baseRoom.assertDestroyed()
            expectNoEvents()
        }
    }

    @Test
    fun `present - initial state`() = runTest {
        val presenter = createSharePresenter()
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialState = awaitItem()
            assertThat(initialState.shareAction.isUninitialized()).isTrue()
        }
    }

    @Test
    fun `present - on room selected error then clear error`() = runTest {
        val presenter = createSharePresenter()
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialState = awaitItem()
            assertThat(initialState.shareAction.isUninitialized()).isTrue()
            presenter.onRoomSelected(listOf(A_ROOM_ID))
            assertThat(awaitItem().shareAction.isLoading()).isTrue()
            val failure = awaitItem()
            assertThat(failure.shareAction.isFailure()).isTrue()
            failure.eventSink.invoke(ShareEvent.ClearError)
            assertThat(awaitItem().shareAction.isUninitialized()).isTrue()
        }
    }

    @Test
    fun `present - on room selected ok`() = runTest {
        val joinedRoom = FakeJoinedRoom(
            liveTimeline = FakeTimeline().apply {
                sendMessageLambda = { _, _, _, _, _ -> Result.success(Unit) }
            },
        )
        val matrixClient = FakeMatrixClient().apply {
            givenGetRoomResult(A_ROOM_ID, joinedRoom)
        }
        val presenter = createSharePresenter(
            matrixClient = matrixClient,
            shareIntentData = ShareIntentData.PlainText(A_MESSAGE),
        )
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialState = awaitItem()
            assertThat(initialState.shareAction.isUninitialized()).isTrue()
            presenter.onRoomSelected(listOf(A_ROOM_ID))
            assertThat(awaitItem().shareAction.isLoading()).isTrue()
            val success = awaitItem()
            assertThat(success.shareAction.isSuccess()).isTrue()
            assertThat(success.shareAction).isEqualTo(AsyncAction.Success(listOf(A_ROOM_ID)))
        }
    }

    @Test
    fun `present - send text ok`() = runTest {
        val joinedRoom = FakeJoinedRoom(
            liveTimeline = FakeTimeline().apply {
                sendMessageLambda = { _, _, _, _, _ -> Result.success(Unit) }
            },
        )
        val matrixClient = FakeMatrixClient().apply {
            givenGetRoomResult(A_ROOM_ID, joinedRoom)
        }
        val presenter = createSharePresenter(
            matrixClient = matrixClient,
            shareIntentData = ShareIntentData.PlainText(A_MESSAGE),
        )
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialState = awaitItem()
            assertThat(initialState.shareAction.isUninitialized()).isTrue()
            presenter.onRoomSelected(listOf(A_ROOM_ID))
            assertThat(awaitItem().shareAction.isLoading()).isTrue()
            val success = awaitItem()
            assertThat(success.shareAction.isSuccess()).isTrue()
            assertThat(success.shareAction).isEqualTo(AsyncAction.Success(listOf(A_ROOM_ID)))
        }
    }

    @Test
    fun `present - send media ok`() = runTest {
        val sendMediaResult = lambdaRecorder<Result<Unit>> { Result.success(Unit) }
        val joinedRoom = FakeJoinedRoom(
            liveTimeline = FakeTimeline(),
        )
        val matrixClient = FakeMatrixClient().apply {
            givenGetRoomResult(A_ROOM_ID, joinedRoom)
        }
        val mediaSender = FakeMediaSender(
            sendMediaResult = sendMediaResult,
        )
        val presenter = createSharePresenter(
            matrixClient = matrixClient,
            shareIntentData = ShareIntentData.Uris(
                text = A_MESSAGE,
                listOf(
                    UriToShare(
                        uri = Uri.parse("content://image.jpg"),
                        mimeType = MimeTypes.Jpeg,
                    )
                )
            ),
            mediaSenderRoomFactory = MediaSenderRoomFactory { mediaSender },
        )
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialState = awaitItem()
            assertThat(initialState.shareAction.isUninitialized()).isTrue()
            presenter.onRoomSelected(listOf(A_ROOM_ID))
            assertThat(awaitItem().shareAction.isLoading()).isTrue()
            val success = awaitItem()
            assertThat(success.shareAction.isSuccess()).isTrue()
            assertThat(success.shareAction).isEqualTo(AsyncAction.Success(listOf(A_ROOM_ID)))
            sendMediaResult.assertions().isCalledOnce()
        }
    }
}

internal fun TestScope.createSharePresenter(
    shareIntentData: ShareIntentData = ShareIntentData.PlainText(A_MESSAGE),
    matrixClient: MatrixClient = FakeMatrixClient(),
    activeRoomsHolder: ActiveRoomsHolder = DefaultActiveRoomsHolder(),
    mediaSenderRoomFactory: MediaSenderRoomFactory = MediaSenderRoomFactory { FakeMediaSender() },
    mediaOptimizationConfigProvider: MediaOptimizationConfigProvider = FakeMediaOptimizationConfigProvider(),
    onSharedData: OnSharedData = OnSharedData {},
): SharePresenter {
    return SharePresenter(
        shareIntentData = shareIntentData,
        sessionCoroutineScope = this,
        matrixClient = matrixClient,
        activeRoomsHolder = activeRoomsHolder,
        mediaSenderRoomFactory = mediaSenderRoomFactory,
        mediaOptimizationConfigProvider = mediaOptimizationConfigProvider,
        onSharedData = onSharedData,
    )
}
