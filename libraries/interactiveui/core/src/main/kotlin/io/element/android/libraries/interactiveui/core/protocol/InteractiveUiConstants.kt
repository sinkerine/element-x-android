/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.core.protocol

/** Ported from element-web apps/web/src/interactive-ui/protocol.ts at bcb08017 (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial). */
object InteractiveUiConstants {
    const val PAYLOAD_KEY: String = "net.15cm.matrix.interactive_ui"
    const val ACTION_KEY: String = "net.15cm.matrix.interactive_ui.action"
    const val VERSION: Int = 1
    const val CLIENT_MAX_PAYLOAD_BYTES: Int = 60_000
    const val CONFIRMATION_OVERDUE_MILLIS: Long = 8_000L
    const val PERSIST_DEBOUNCE_MILLIS: Long = 200L
}
