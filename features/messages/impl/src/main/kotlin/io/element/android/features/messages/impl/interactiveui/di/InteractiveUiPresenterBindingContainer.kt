/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.interactiveui.di

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoMap
import io.element.android.features.messages.impl.interactiveui.InteractiveUiPresenter
import io.element.android.features.messages.impl.timeline.di.TimelineItemEventContentKey
import io.element.android.features.messages.impl.timeline.di.TimelineItemPresenterFactory
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemInteractiveUiContent
import io.element.android.libraries.di.RoomScope

@BindingContainer
@ContributesTo(RoomScope::class)
interface InteractiveUiPresenterBindingContainer {
    @Binds
    @IntoMap
    @TimelineItemEventContentKey(TimelineItemInteractiveUiContent::class)
    fun bindInteractiveUiPresenterFactory(factory: InteractiveUiPresenter.Factory): TimelineItemPresenterFactory<*, *>
}
