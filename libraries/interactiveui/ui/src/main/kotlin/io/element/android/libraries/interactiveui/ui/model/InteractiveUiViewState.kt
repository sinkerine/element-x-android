/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.ui.model

import androidx.compose.runtime.Immutable
import io.element.android.libraries.interactiveui.core.session.InteractiveUiNotice
import io.element.android.libraries.interactiveui.core.session.InteractiveUiPhase
import io.element.android.libraries.interactiveui.core.session.InteractiveUiUrlRequest

/** Ported from element-web packages/shared-components/src/room/timeline/event-tile/body/InteractiveUiView/types.ts
 * at bcb08017 (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial).
 */
@Immutable
data class InteractiveUiViewState(val title: String?,
    val phase: InteractiveUiPhase,
    val notice: InteractiveUiNotice?,
    val senderDisplayName: String,
    val confirmationOverdue: Boolean,
    val showAsText: Boolean,
    val urlRequest: InteractiveUiUrlRequest?,
    val root: InteractiveUiRenderNode,
    val eventSink: (InteractiveUiViewEvent) -> Unit,
)
