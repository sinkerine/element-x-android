/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.share.impl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.element.android.features.share.api.OnSharedData
import io.element.android.features.share.api.ShareIntentData
import io.element.android.features.share.api.UriToShare
import io.element.android.libraries.androidutils.file.safeDelete
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.architecture.runCatchingUpdatingState
import io.element.android.libraries.core.bool.orFalse
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.mediaupload.api.MAX_GALLERY_ITEMS
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfigProvider
import io.element.android.libraries.mediaupload.api.MediaSender
import io.element.android.libraries.mediaupload.api.MediaSenderRoomFactory
import io.element.android.libraries.mediaupload.api.MediaUploadInfo
import io.element.android.libraries.mediaupload.api.allFiles
import io.element.android.services.appnavstate.api.ActiveRoomsHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

@AssistedInject
class SharePresenter(
    @Assisted private val shareIntentData: ShareIntentData,
    @SessionCoroutineScope
    private val sessionCoroutineScope: CoroutineScope,
    private val matrixClient: MatrixClient,
    private val mediaSenderRoomFactory: MediaSenderRoomFactory,
    private val activeRoomsHolder: ActiveRoomsHolder,
    private val mediaOptimizationConfigProvider: MediaOptimizationConfigProvider,
    private val onSharedData: OnSharedData,
) : Presenter<ShareState> {
    @AssistedFactory
    fun interface Factory {
        fun create(shareIntentData: ShareIntentData): SharePresenter
    }

    private val shareActionState: MutableState<AsyncAction<List<RoomId>>> = mutableStateOf(AsyncAction.Uninitialized)

    fun onRoomSelected(roomIds: List<RoomId>) {
        sessionCoroutineScope.share(shareIntentData, roomIds)
    }

    @Composable
    override fun present(): ShareState {
        fun handleEvent(event: ShareEvent) {
            when (event) {
                ShareEvent.ClearError -> shareActionState.value = AsyncAction.Uninitialized
            }
        }

        return ShareState(
            shareAction = shareActionState.value,
            eventSink = ::handleEvent,
        )
    }

    private suspend fun getJoinedRoom(roomId: RoomId): JoinedRoom? {
        return activeRoomsHolder.getActiveRoom(matrixClient.sessionId)
            ?.takeIf { it.roomId == roomId }
            ?: matrixClient.getJoinedRoom(roomId)
    }

    /** Pre-process every file in order, then send them as one gallery; always cleans up. */
    private suspend fun MediaSender.sendSharedGallery(files: List<UriToShare>, caption: String?): Result<Unit> {
        val infos = mutableListOf<MediaUploadInfo>()
        try {
            for (file in files) {
                val result = preProcessMedia(file.uri, file.mimeType, mediaOptimizationConfigProvider.get())
                val error = result.exceptionOrNull()
                if (error is CancellationException) throw error
                if (error != null) return Result.failure(error)
                infos.add(result.getOrThrow())
            }
            val result = sendGallery(infos, caption, formattedCaption = null, inReplyToEventId = null)
            (result.exceptionOrNull() as? CancellationException)?.let { throw it }
            return result
        } finally {
            infos.forEach { info -> info.allFiles().forEach { it.safeDelete() } }
            cleanUp()
        }
    }

    private fun CoroutineScope.share(
        shareIntentData: ShareIntentData,
        roomIds: List<RoomId>,
    ) = launch {
        suspend {
            val result = when (shareIntentData) {
                is ShareIntentData.PlainText -> {
                    roomIds
                        .map { roomId ->
                            getJoinedRoom(roomId)?.liveTimeline?.sendMessage(
                                body = shareIntentData.content,
                                htmlBody = null,
                                intentionalMentions = emptyList(),
                            )?.isSuccess.orFalse()
                        }
                        .all { it }
                }
                is ShareIntentData.Uris -> {
                    val filesToShare = shareIntentData.uris
                    if (filesToShare.isEmpty()) {
                        false
                    } else {
                        roomIds
                            .map { roomId ->
                                val room = getJoinedRoom(roomId) ?: return@map false
                                val mediaSender = mediaSenderRoomFactory.create(room = room)
                                if (filesToShare.size in 2..MAX_GALLERY_ITEMS) {
                                    try {
                                        mediaSender.sendSharedGallery(filesToShare, shareIntentData.text).isSuccess
                                    } finally {
                                        if (activeRoomsHolder.getActiveRoomMatching(matrixClient.sessionId, roomId) == null) {
                                            room.destroy()
                                        }
                                    }
                                } else {
                                    filesToShare
                                    .map { fileToShare ->
                                        val result = mediaSender.sendMedia(
                                            caption = shareIntentData.text,
                                            uri = fileToShare.uri,
                                            mimeType = fileToShare.mimeType,
                                            mediaOptimizationConfig = mediaOptimizationConfigProvider.get(),
                                        )
                                        // If the coroutine was cancelled, destroy the room and rethrow the exception
                                        val cancellationException = result.exceptionOrNull() as? CancellationException
                                        if (cancellationException != null) {
                                            if (activeRoomsHolder.getActiveRoomMatching(matrixClient.sessionId, roomId) == null) {
                                                room.destroy()
                                            }
                                            throw cancellationException
                                        }
                                        result.isSuccess
                                    }
                                    .all { isSuccess -> isSuccess }
                                    .also {
                                        if (activeRoomsHolder.getActiveRoomMatching(matrixClient.sessionId, roomId) == null) {
                                            room.destroy()
                                        }
                                    }
                                }
                            }
                            .all { it }
                    }
                }
            }

            // Handle post-processing of shared data
            onSharedData(shareIntentData)

            if (!result) {
                error("Failed to handle incoming share intent")
            }
            roomIds
        }.runCatchingUpdatingState(shareActionState)
    }
}
