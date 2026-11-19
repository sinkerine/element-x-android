/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.share.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.features.share.api.OnSharedData
import io.element.android.features.share.api.ShareIntentData
import io.element.android.features.share.api.UriToShare
import io.element.android.libraries.androidutils.file.safeDelete
import io.element.android.libraries.core.bool.orFalse
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.mediaupload.api.MAX_GALLERY_ITEMS
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfig
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfigProviderFactory
import io.element.android.libraries.mediaupload.api.MediaSender
import io.element.android.libraries.mediaupload.api.MediaSenderRoomFactory
import io.element.android.libraries.mediaupload.api.MediaUploadInfo
import io.element.android.libraries.mediaupload.api.allFiles
import io.element.android.services.appnavstate.api.ActiveRoomsHolder
import kotlin.coroutines.cancellation.CancellationException

@ContributesBinding(AppScope::class)
class DefaultShareDataSender(
    private val mediaSenderRoomFactory: MediaSenderRoomFactory,
    private val mediaOptimizationConfigProviderFactory: MediaOptimizationConfigProviderFactory,
    private val activeRoomsHolder: ActiveRoomsHolder,
    private val onSharedData: OnSharedData,
) : ShareDataSender {
    override suspend fun send(
        client: MatrixClient,
        shareIntentData: ShareIntentData,
        roomIds: List<RoomId>,
    ): Result<Unit> = runCatchingExceptions {
        val result = when (shareIntentData) {
            is ShareIntentData.PlainText -> {
                roomIds
                    .map { roomId ->
                        client.findJoinedRoom(roomId)?.liveTimeline?.sendMessage(
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
                    // The media optimization config is the same for all the files and all the rooms
                    val mediaOptimizationConfig = mediaOptimizationConfigProviderFactory.create(client).get()
                    roomIds
                        .map { roomId ->
                            val room = client.findJoinedRoom(roomId) ?: return@map false
                            val mediaSender = mediaSenderRoomFactory.create(client = client, room = room)
                            try {
                                if (filesToShare.size in 2..MAX_GALLERY_ITEMS) {
                                    mediaSender.sendSharedGallery(filesToShare, shareIntentData.text, mediaOptimizationConfig).isSuccess
                                } else {
                                    filesToShare.map { fileToShare ->
                                        val result = mediaSender.sendMedia(
                                            caption = shareIntentData.text,
                                            uri = fileToShare.uri,
                                            mimeType = fileToShare.mimeType,
                                            mediaOptimizationConfig = mediaOptimizationConfig,
                                        )
                                        (result.exceptionOrNull() as? CancellationException)?.let { throw it }
                                        result.isSuccess
                                    }.all { it }
                                }
                            } finally {
                                if (activeRoomsHolder.getActiveRoomMatching(client.sessionId, roomId) == null) {
                                    room.destroy()
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
    }

    private suspend fun MediaSender.sendSharedGallery(
        files: List<UriToShare>,
        caption: String?,
        mediaOptimizationConfig: MediaOptimizationConfig,
    ): Result<Unit> {
        val infos = mutableListOf<MediaUploadInfo>()
        try {
            for (file in files) {
                val result = preProcessMedia(file.uri, file.mimeType, mediaOptimizationConfig)
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

    private suspend fun MatrixClient.findJoinedRoom(roomId: RoomId): JoinedRoom? {
        return activeRoomsHolder.getActiveRoom(sessionId)
            ?.takeIf { it.roomId == roomId }
            ?: getJoinedRoom(roomId)
    }
}
