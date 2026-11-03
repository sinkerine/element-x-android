/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.core.session

/** Ported from element-web apps/web/src/interactive-ui/protocol.ts at bcb08017 (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial). */
enum class InteractiveUiPhase { Active, Sending, AwaitingConfirmation, Submitted, Expired, Superseded }
sealed interface InteractiveUiNotice {
    data class PayloadText(val text: String) : InteractiveUiNotice
    data object SendFailed : InteractiveUiNotice
    data object WaitingForSender : InteractiveUiNotice
}
data class InteractiveUiTableSort(val columnKey: String, val descending: Boolean)
sealed interface InteractiveUiUrlRequest {
    data class Confirming(val url: String) : InteractiveUiUrlRequest
    data class Approved(val url: String) : InteractiveUiUrlRequest
}
