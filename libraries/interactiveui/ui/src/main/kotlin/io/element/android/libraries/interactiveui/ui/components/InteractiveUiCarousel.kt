/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.ui.components

import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.swipe.SwipeSensitivity
import io.element.android.libraries.designsystem.theme.components.OutlinedButton
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.utils.BlockAppDrawerGestures
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiNode
import io.element.android.libraries.interactiveui.ui.R
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiRenderNode
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiViewEvent
import io.element.android.libraries.ui.strings.CommonStrings
import kotlinx.coroutines.flow.distinctUntilChanged

/** Ported from element-web packages/shared-components/src/room/timeline/event-tile/body/InteractiveUiView/InteractiveUiView.tsx
 * at bcb08017 (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial).
 */
@Composable
internal fun InteractiveUiCarousel(
    node: InteractiveUiRenderNode.Carousel,
    enabled: Boolean,
    sink: (InteractiveUiViewEvent) -> Unit,
    onShowMedia: () -> Unit,
) {
    if (node.items.isEmpty()) return
    val index = node.index.coerceIn(node.items.indices)
    Column(Modifier.fillMaxWidth().semantics { contentDescription = node.label }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.screen_interactive_ui_item_position, index + 1, node.items.size),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
        if (node.items.size == 1) {
            CarouselItem(node.id, node.items.first(), enabled, sink, onShowMedia)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(stringResource(
                    R.string.screen_interactive_ui_previous
                ), { sink(InteractiveUiViewEvent.SetCarouselIndex(node.id, index - 1)) }, enabled =
                    enabled && index > 0)
                OutlinedButton(stringResource(CommonStrings.action_next), { sink(InteractiveUiViewEvent.SetCarouselIndex(node.id, index + 1)) }, enabled =
                    enabled && index < node.items.lastIndex)
            }
            CarouselPager(node, enabled, sink, onShowMedia)
        }
    }
}

@Composable
private fun CarouselPager(node: InteractiveUiRenderNode.Carousel, enabled: Boolean, sink: (InteractiveUiViewEvent) -> Unit, onShowMedia: () -> Unit) {
    val pager = rememberPagerState(initialPage = node.index.coerceIn(node.items.indices)) { node.items.size }
    val latestNode by rememberUpdatedState(node)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestSink by rememberUpdatedState(sink)
    // Caller-driven movement never reports back as a user swipe. Observe the drag lifecycle, not layout/page changes.
    LaunchedEffect(pager) {
        var wasScrolling = false
        var startIndex = latestNode.index
        snapshotFlow { pager.isScrollInProgress to pager.settledPage }.distinctUntilChanged().collect { (scrolling, settled) ->
            if (scrolling && !wasScrolling) startIndex = latestNode.index
            if (wasScrolling && !scrolling && latestEnabled) {
                // A long drag can cross several pages before the fling limiter runs. One gesture means one item.
                val target = settled.coerceIn(maxOf(0, startIndex - 1), minOf(latestNode.items.lastIndex, startIndex + 1))
                if (target != latestNode.index) latestSink(InteractiveUiViewEvent.SetCarouselIndex(latestNode.id, target))
                if (settled != target) pager.scrollToPage(target)
            }
            wasScrolling = scrolling
        }
    }
    LaunchedEffect(node.index) {
        val target = node.index.coerceIn(node.items.indices)
        if (pager.currentPage != target) pager.scrollToPage(target)
    }
    if (pager.isScrollInProgress) BlockAppDrawerGestures()
    BoxWithConstraints {
        SwipeSensitivity(1f / 3f) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxWidth().testTag("interactive-ui-carousel-${node.id}"),
                pageSize = PageSize.Fixed(minOf(maxWidth * 0.86f, 300.dp)),
                pageSpacing = 12.dp,
                userScrollEnabled = enabled,
                key = { node.items[it].id },
                snapPosition = SnapPosition.Start,
                flingBehavior = PagerDefaults.flingBehavior(pager, pagerSnapDistance = PagerSnapDistance.atMost(1)),
            ) { page -> CarouselItem(node.id, node.items[page], enabled, sink, onShowMedia) }
        }
    }
}

@Composable
private fun CarouselItem(
    id: String,
    item: InteractiveUiRenderNode.Carousel.Item,
    enabled: Boolean,
    sink: (InteractiveUiViewEvent) -> Unit,
    onShowMedia: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InteractiveUiImage(item.alt, item.source, null, item.hidden, onShowMedia)
        Text(item.title, style = ElementTheme.typography.fontHeadingSmMedium)
        item.subtitle?.let { Text(it) }
        item.meta.take(3).forEach { Text(it, style = ElementTheme.typography.fontBodySmRegular) }
        item.badge?.let { InteractiveUiBadge(it.first, it.second) }
        item.actionLabel?.let { label ->
            ActionButton(
                label,
                enabled,
                false,
                InteractiveUiNode.ButtonVariant.Secondary,
                item.external,
                onClick = { sink(InteractiveUiViewEvent.ActivateCarouselItem(id, item.id)) }
            )
        }
    }
}
