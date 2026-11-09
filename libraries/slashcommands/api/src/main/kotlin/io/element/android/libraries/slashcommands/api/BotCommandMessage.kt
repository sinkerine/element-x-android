/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.slashcommands.api

import io.element.android.libraries.matrix.api.core.UserId

/** Composer text resolved to a bot command published in the room, ready to send. */
data class BotCommandMessage(
    val botUserId: UserId,
    /** Plain canonical body: "/<command>" or "/<command> <args>". */
    val body: String,
    /** JSON object holding `org.matrix.msc4391.command`, or null when the arguments do not map cleanly. */
    val extraContentJson: String?,
)
