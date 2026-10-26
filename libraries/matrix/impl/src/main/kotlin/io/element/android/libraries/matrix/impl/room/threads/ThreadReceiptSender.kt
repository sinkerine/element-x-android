/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.room.threads

import io.element.android.libraries.matrix.api.room.threads.InboxThread
import org.matrix.rustcomponents.sdk.ReceiptThread
import org.matrix.rustcomponents.sdk.ReceiptType

/** Explicit targets avoid timeline.markAsRead accidentally including events received during Undo. */
internal suspend fun sendCapturedThreadReceipt(
    snapshot: InboxThread,
    publicReceipts: Boolean,
    send: suspend (ReceiptType, ReceiptThread, String) -> Unit,
) {
    send(
        if (publicReceipts) ReceiptType.READ else ReceiptType.READ_PRIVATE,
        ReceiptThread.Thread(snapshot.key.threadId.value),
        (snapshot.latestEvent ?: snapshot.rootEvent).eventId.value,
    )
}
