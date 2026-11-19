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
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.media.FileInfo
import io.element.android.libraries.matrix.test.AN_EXCEPTION
import io.element.android.libraries.matrix.test.A_MESSAGE
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.A_SESSION_ID_2
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.FakeMatrixClientProvider
import io.element.android.libraries.matrix.test.room.FakeBaseRoom
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfigProviderFactory
import io.element.android.libraries.mediaupload.api.MediaSenderRoomFactory
import io.element.android.libraries.mediaupload.api.MediaUploadInfo
import io.element.android.libraries.mediaupload.test.FakeMediaOptimizationConfigProvider
import io.element.android.libraries.mediaupload.test.FakeMediaSender
import io.element.android.services.appnavstate.impl.DefaultActiveRoomsHolder
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.lambda.value
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.CompletableDeferred
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
        val presenter = createGallerySharePresenter(
            matrixClient = client,
            shareIntentData = ShareIntentData.Uris(A_MESSAGE, files),
            mediaSender = sender,
        )
        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem()
            presenter.onRoomSelected(client.sessionId, rooms)
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
        val presenter = createGallerySharePresenter(
            matrixClient = client,
            shareIntentData = ShareIntentData.Uris(A_MESSAGE, List(61) { UriToShare(Uri.parse("content://files/$it"), "text/plain") }),
            mediaSender = sender,
        )
        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem()
            presenter.onRoomSelected(client.sessionId, listOf(A_ROOM_ID))
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
        val presenter = createGallerySharePresenter(
            matrixClient = client,
            shareIntentData = ShareIntentData.Uris(null, List(3) { UriToShare(Uri.parse("content://files/$it"), "text/plain") }),
            mediaSender = sender,
        )
        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem()
            presenter.onRoomSelected(client.sessionId, listOf(A_ROOM_ID))
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
        val presenter = createGallerySharePresenter(
            matrixClient = client,
            shareIntentData = ShareIntentData.Uris(null, List(2) { UriToShare(Uri.parse("content://files/$it"), "text/plain") }),
            mediaSender = sender,
            onSharedData = OnSharedData { shared = true },
        )
        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem()
            presenter.onRoomSelected(client.sessionId, listOf(A_ROOM_ID))
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
        val presenter = createSharePresenter(
            shareDataSender = FakeShareDataSender { _, _, _ -> Result.failure(AN_EXCEPTION) },
        )
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialState = awaitItem()
            assertThat(initialState.shareAction.isUninitialized()).isTrue()
            presenter.onRoomSelected(A_SESSION_ID, listOf(A_ROOM_ID))
            assertThat(awaitItem().shareAction.isLoading()).isTrue()
            val failure = awaitItem()
            assertThat(failure.shareAction).isEqualTo(AsyncAction.Failure(AN_EXCEPTION))
            failure.eventSink.invoke(ShareEvent.ClearError)
            assertThat(awaitItem().shareAction.isUninitialized()).isTrue()
        }
    }

    @Test
    fun `present - on room selected ok`() = runTest {
        val client = FakeMatrixClient(sessionId = A_SESSION_ID)
        val shareIntentData = ShareIntentData.PlainText(A_MESSAGE)
        val sendResult = lambdaRecorder<MatrixClient, ShareIntentData, List<RoomId>, Result<Unit>> { _, _, _ -> Result.success(Unit) }
        val presenter = createSharePresenter(
            shareIntentData = shareIntentData,
            matrixClientProvider = FakeMatrixClientProvider { Result.success(client) },
            shareDataSender = FakeShareDataSender(sendResult),
        )
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialState = awaitItem()
            assertThat(initialState.shareAction.isUninitialized()).isTrue()
            presenter.onRoomSelected(A_SESSION_ID, listOf(A_ROOM_ID))
            assertThat(awaitItem().shareAction.isLoading()).isTrue()
            val success = awaitItem()
            assertThat(success.shareAction).isEqualTo(AsyncAction.Success(ShareResult(A_SESSION_ID, listOf(A_ROOM_ID))))
            sendResult.assertions().isCalledOnce().with(value(client), value(shareIntentData), value(listOf(A_ROOM_ID)))
        }
    }

    @Test
    fun `present - on room selected from another session ok`() = runTest {
        val getClient = lambdaRecorder<SessionId, Result<MatrixClient>> { sessionId -> Result.success(FakeMatrixClient(sessionId = sessionId)) }
        val sendResult = lambdaRecorder<MatrixClient, ShareIntentData, List<RoomId>, Result<Unit>> { client, _, _ ->
            assertThat(client.sessionId).isEqualTo(A_SESSION_ID_2)
            Result.success(Unit)
        }
        val presenter = createSharePresenter(
            matrixClientProvider = FakeMatrixClientProvider { getClient(it) },
            shareDataSender = FakeShareDataSender(sendResult),
        )
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            skipItems(1)
            presenter.onRoomSelected(A_SESSION_ID_2, listOf(A_ROOM_ID))
            assertThat(awaitItem().shareAction.isLoading()).isTrue()
            val success = awaitItem()
            assertThat(success.shareAction).isEqualTo(AsyncAction.Success(ShareResult(A_SESSION_ID_2, listOf(A_ROOM_ID))))
            getClient.assertions().isCalledOnce().with(value(A_SESSION_ID_2))
            sendResult.assertions().isCalledOnce()
        }
    }

    @Test
    fun `present - on room selected, failure to get the client`() = runTest {
        val sendResult = lambdaRecorder<MatrixClient, ShareIntentData, List<RoomId>, Result<Unit>> { _, _, _ -> Result.success(Unit) }
        val presenter = createSharePresenter(
            matrixClientProvider = FakeMatrixClientProvider { Result.failure(AN_EXCEPTION) },
            shareDataSender = FakeShareDataSender(sendResult),
        )
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            skipItems(1)
            presenter.onRoomSelected(A_SESSION_ID_2, listOf(A_ROOM_ID))
            assertThat(awaitItem().shareAction.isLoading()).isTrue()
            assertThat(awaitItem().shareAction).isEqualTo(AsyncAction.Failure(AN_EXCEPTION))
            sendResult.assertions().isNeverCalled()
        }
    }

    @Test
    fun `present - on room selected while sharing is ignored`() = runTest {
        val completable = CompletableDeferred<Result<Unit>>()
        val sendResult = lambdaRecorder<MatrixClient, ShareIntentData, List<RoomId>, Result<Unit>> { _, _, _ -> Result.success(Unit) }
        val presenter = createSharePresenter(
            shareDataSender = object : ShareDataSender {
                override suspend fun send(client: MatrixClient, shareIntentData: ShareIntentData, roomIds: List<RoomId>): Result<Unit> {
                    sendResult(client, shareIntentData, roomIds)
                    return completable.await()
                }
            },
        )
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            skipItems(1)
            presenter.onRoomSelected(A_SESSION_ID, listOf(A_ROOM_ID))
            assertThat(awaitItem().shareAction.isLoading()).isTrue()
            presenter.onRoomSelected(A_SESSION_ID_2, listOf(A_ROOM_ID))
            completable.complete(Result.success(Unit))
            val success = awaitItem()
            // The second selection has been ignored
            assertThat(success.shareAction).isEqualTo(AsyncAction.Success(ShareResult(A_SESSION_ID, listOf(A_ROOM_ID))))
            sendResult.assertions().isCalledOnce()
        }
    }
}

private fun TestScope.createGallerySharePresenter(
    matrixClient: MatrixClient,
    shareIntentData: ShareIntentData,
    mediaSender: FakeMediaSender,
    onSharedData: OnSharedData = OnSharedData {},
) = createSharePresenter(
    shareIntentData = shareIntentData,
    matrixClientProvider = FakeMatrixClientProvider { Result.success(matrixClient) },
    shareDataSender = DefaultShareDataSender(
        mediaSenderRoomFactory = MediaSenderRoomFactory { _, _ -> mediaSender },
        mediaOptimizationConfigProviderFactory = MediaOptimizationConfigProviderFactory { FakeMediaOptimizationConfigProvider() },
        activeRoomsHolder = DefaultActiveRoomsHolder(),
        onSharedData = onSharedData,
    ),
)

internal fun TestScope.createSharePresenter(
    shareIntentData: ShareIntentData = ShareIntentData.PlainText(A_MESSAGE),
    matrixClientProvider: FakeMatrixClientProvider = FakeMatrixClientProvider(),
    shareDataSender: ShareDataSender = FakeShareDataSender(),
): SharePresenter {
    return SharePresenter(
        shareIntentData = shareIntentData,
        appCoroutineScope = this,
        matrixClientProvider = matrixClientProvider,
        shareDataSender = shareDataSender,
    )
}
