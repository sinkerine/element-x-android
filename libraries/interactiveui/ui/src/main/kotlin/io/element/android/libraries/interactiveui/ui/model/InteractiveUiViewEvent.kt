/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.ui.model

import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiValue

/** Ported from element-web packages/shared-components/src/room/timeline/event-tile/body/InteractiveUiView/types.ts
 * at bcb08017 (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial).
 */
sealed interface InteractiveUiViewEvent {
    data class SetValue(val nodeId: String, val value: InteractiveUiValue) : InteractiveUiViewEvent
    data class Activate(val nodeId: String) : InteractiveUiViewEvent
    data class SubmitForm(val nodeId: String) : InteractiveUiViewEvent
    data class SelectGraphNode(val nodeId: String, val graphNodeId: String?) : InteractiveUiViewEvent
    data class SetGraphListMode(val nodeId: String, val asList: Boolean) : InteractiveUiViewEvent
    data class SetChartDataMode(val nodeId: String, val showData: Boolean) : InteractiveUiViewEvent
    data class SortTable(val nodeId: String, val columnKey: String) : InteractiveUiViewEvent
    data class SetShowAsText(val showAsText: Boolean) : InteractiveUiViewEvent
    data class SetCarouselIndex(val nodeId: String, val index: Int) : InteractiveUiViewEvent
    data class ActivateCarouselItem(val nodeId: String, val itemId: String) : InteractiveUiViewEvent
    data class OpenLink(val nodeId: String) : InteractiveUiViewEvent
    data object ConfirmOpenUrl : InteractiveUiViewEvent
    data object ClearUrlRequest : InteractiveUiViewEvent
    data object RetryAfterOverdue : InteractiveUiViewEvent
}
