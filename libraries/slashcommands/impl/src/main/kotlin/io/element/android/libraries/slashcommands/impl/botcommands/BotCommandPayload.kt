/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.slashcommands.impl.botcommands

import io.element.android.libraries.matrix.api.core.UserId

internal data class BotCommandPayload(val botUserId: UserId, val body: String, val extraContentJson: String?)
