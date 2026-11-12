/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline

import android.text.Layout
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import io.element.android.libraries.matrix.api.core.UniqueId
import kotlin.math.abs
import kotlin.math.roundToInt

/** Keeps the reading position rather than the bottom edge of a growing reverse-list item. */
internal class TimelineReadingPosition(private val list: LazyListState) {
    private val items = mutableMapOf<UniqueId, ReadingItem>()
    private var saved: Position? = null
    private var correcting = false
    private var correctionPending = false
    var keys: List<UniqueId> = emptyList()

    fun reset() {
        saved = null
        correctionPending = false
    }

    fun onLayout(viewport: LayoutCoordinates, isLive: Boolean) {
        if (correcting || !viewport.isAttached) return
        val info = list.layoutInfo
        val firstKey = info.visibleItemsInfo.firstOrNull { it.index == list.firstVisibleItemIndex }?.key ?: return
        val sizes = info.visibleItemsInfo.associate { it.key to it.size }
        val top = viewport.positionInRoot().y
        val bottom = top + viewport.size.height
        val previous = saved
        val sizeChanged = previous?.sizes?.any { (key, size) -> sizes[key]?.let { it != size } == true } == true
        val positionChanged = previous != null &&
            (previous.firstKey != firstKey || previous.scrollOffset != list.firstVisibleItemScrollOffset)
        val manualScroll = positionChanged && previous.keys == keys && !sizeChanged && !correctionPending
        correctionPending = false
        if (previous == null) {
            capture(firstKey, sizes, top, bottom, viewport)
            return
        }
        if (list.isScrollInProgress || manualScroll || previous.viewportSize != viewport.size) {
            capture(firstKey, sizes, top, bottom, viewport)
            return
        }
        if (previous.atBottom && isLive) {
            if (list.canScrollBackward) {
                correctionPending = true
                list.requestScrollToItem(0)
            } else {
                capture(firstKey, sizes, top, bottom, viewport)
            }
            return
        }
        val anchor = previous.anchor
        val index = keys.indexOf(anchor.key)
        if (index < 0) {
            capture(firstKey, sizes, top, bottom, viewport)
            return
        }
        val item = items[anchor.key]
        val coordinates = item?.coordinates?.takeIf { it.isAttached }
        if (coordinates == null || info.visibleItemsInfo.none { it.key == anchor.key }) {
            // A large edit can move the anchor entirely outside the lazy window. Bring its key
            // back first; its newly measured text/height can then refine this estimate.
            correctionPending = true
            list.requestScrollToItem(index, (anchor.rowTop - top + anchor.rowSize - info.viewportEndOffset).roundToInt())
            return
        }
        val rowTop = coordinates.positionInRoot().y
        val text = anchor.textKey?.let { item.texts[it] }
        val textCoordinates = text?.coordinates?.takeIf { it.isAttached }
        val passageOffset = if (textCoordinates != null && text.layout != null && anchor.textAnchor != null) {
            anchor.textAnchor.resolve(text.layout!!)?.let { textCoordinates.positionInRoot().y - rowTop + it }
        } else {
            null
        }
        val delta = rowTop + (passageOffset ?: anchor.passageOffset) - anchor.passageTop
        if (abs(delta) > 0.5f) {
            // Global positioning runs after measurement and before drawing. The synchronous delta
            // remeasures the lazy list without starting an animation or taking over an active drag.
            correcting = true
            correctionPending = true
            val consumed = try {
                list.dispatchRawDelta(-delta)
            } finally {
                correcting = false
            }
            if (abs(consumed) < 0.5f) capture(firstKey, sizes, top, bottom, viewport)
        } else {
            capture(firstKey, sizes, top, bottom, viewport)
        }
    }

    private fun capture(
        firstKey: Any,
        sizes: Map<Any, Int>,
        top: Float,
        bottom: Float,
        viewport: LayoutCoordinates,
    ) {
        val item = items.entries.mapNotNull { (key, item) ->
            val coordinates = item.coordinates?.takeIf { it.isAttached } ?: return@mapNotNull null
            val y = coordinates.positionInRoot().y
            if (key !in sizes || y >= bottom || y + coordinates.size.height <= top) return@mapNotNull null
            Triple(key, item, y)
        }.minByOrNull { it.third } ?: run {
            reset()
            return
        }
        val (key, row, rowTop) = item
        val textKey = row.texts.entries.firstOrNull { (_, text) ->
            val coordinates = text.coordinates?.takeIf { it.isAttached } ?: return@firstOrNull false
            val y = coordinates.positionInRoot().y
            text.layout != null && y < bottom && y + coordinates.size.height > top
        }?.key
        val text = textKey?.let { row.texts[it] }
        val textTop = text?.coordinates?.positionInRoot()?.y
        val textAnchor = text?.layout?.let { TimelineTextAnchor.capture(it, (top - textTop!!).coerceAtLeast(0f).roundToInt()) }
        val offset = if (textAnchor != null) textTop!! - rowTop + textAnchor.lineTop else 0f
        saved = Position(
            firstKey = firstKey,
            scrollOffset = list.firstVisibleItemScrollOffset,
            sizes = sizes,
            keys = keys,
            viewportSize = viewport.size,
            atBottom = !list.canScrollBackward,
            anchor = Anchor(key, rowTop, sizes.getValue(key), textKey, textAnchor, offset, rowTop + offset),
        )
    }

    @Composable
    fun Item(key: UniqueId, modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
        val item = remember(this, key) { ReadingItem() }
        DisposableEffect(this, key) {
            items[key] = item
            onDispose { items.remove(key) }
        }
        CompositionLocalProvider(LocalReadingItem provides item) {
            content(modifier.onPlaced { item.coordinates = it })
        }
    }

    private data class Anchor(
        val key: UniqueId,
        val rowTop: Float,
        val rowSize: Int,
        val textKey: String?,
        val textAnchor: TimelineTextAnchor?,
        val passageOffset: Float,
        val passageTop: Float,
    )

    private data class Position(
        val firstKey: Any,
        val scrollOffset: Int,
        val sizes: Map<Any, Int>,
        val keys: List<UniqueId>,
        val viewportSize: androidx.compose.ui.unit.IntSize,
        val atBottom: Boolean,
        val anchor: Anchor,
    )
}

private class ReadingItem {
    var coordinates: LayoutCoordinates? = null
    val texts = mutableMapOf<String, TimelineReadingText>()
}

private val LocalReadingItem = staticCompositionLocalOf<ReadingItem?> { null }

internal class TimelineReadingText {
    var coordinates: LayoutCoordinates? = null
    var layout: Layout? = null
}

@Composable
internal fun rememberTimelineReadingText(key: String = "body"): TimelineReadingText? {
    val item = LocalReadingItem.current ?: return null
    // ContentAvoidingLayout can replace a subcomposition while measuring. Retain the slot's
    // identity so the anchor never reads the outgoing TextView's still-attached coordinates.
    return remember(item, key) { item.texts.getOrPut(key) { TimelineReadingText() } }
}

/** Stores immutable text evidence; Android can replace or mutate Layout/text objects on an edit. */
internal data class TimelineTextAnchor(val characterOffset: Int, val prefix: String, val lineTop: Int) {
    fun resolve(layout: Layout): Int? {
        val text = layout.text.toString()
        val offset = if (text.startsWith(prefix, characterOffset)) {
            characterOffset
        } else {
            val match = text.indexOf(prefix)
            match.takeIf { it >= 0 && text.indexOf(prefix, it + 1) < 0 } ?: return null
        }
        return layout.getLineTop(layout.getLineForOffset(offset))
    }

    companion object {
        fun capture(layout: Layout, viewportTop: Int): TimelineTextAnchor? {
            if (layout.text.isEmpty()) return null
            var line = layout.getLineForVertical(viewportTop)
            if (layout.getLineTop(line) < viewportTop && line < layout.lineCount - 1) line++
            while (line < layout.lineCount) {
                val offset = layout.getLineStart(line)
                val prefix = layout.text.subSequence(offset, minOf(layout.getLineEnd(line), offset + 64)).toString()
                if (prefix.isNotBlank()) return TimelineTextAnchor(offset, prefix, layout.getLineTop(line))
                line++
            }
            return null
        }
    }
}
