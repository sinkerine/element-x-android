/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.maths.impl.layout

import android.content.Context
import android.util.LruCache
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.maths.api.MathsLayout
import io.element.android.libraries.maths.api.MathsLayoutEngine
import io.ratex.DisplayList
import io.ratex.RaTeXEngine
import io.ratex.RaTeXException
import io.ratex.RaTeXFontLoader
import timber.log.Timber

internal const val MAX_WIDTH_EM = 500f
internal const val MAX_HEIGHT_EM = 100f
internal const val MAX_DISPLAY_ITEMS = 20_000
internal const val MAX_CACHED_LAYOUTS = 256

internal fun DisplayList.isWithinLimits(): Boolean = width.isFinite() && height.isFinite() && depth.isFinite() &&
    width >= 0 && height >= 0 && depth >= 0 && width <= MAX_WIDTH_EM && height + depth <= MAX_HEIGHT_EM && items.size <= MAX_DISPLAY_ITEMS

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class RatexMathsLayoutEngine(
    @ApplicationContext private val context: Context,
) : MathsLayoutEngine {
    private val cache = LruCache<Pair<String, Boolean>, Any>(MAX_CACHED_LAYOUTS)

    @Synchronized
    override fun layout(tex: String, displayMode: Boolean): MathsLayout? {
        if (!MathsPrecheck.isAllowed(tex) || unavailable) return null
        val key = tex to displayMode
        cache.get(key)?.let { return it as? MathsLayout }
        val result = try {
            RaTeXFontLoader.ensureLoaded(context)
            RaTeXEngine.parseBlocking(tex, displayMode, DEFAULT_COLOR_SENTINEL)
                .takeIf { it.isWithinLimits() }?.let(::RatexMathsLayout)
        } catch (_: RaTeXException) {
            Timber.d("Maths layout rejected (length=%d)", tex.length)
            null
        } catch (error: LinkageError) {
            synchronized(Failed) {
                if (!unavailable) Timber.w("Maths engine unavailable (%s)", error.javaClass.simpleName)
                unavailable = true
            }
            null
        } catch (error: Exception) {
            Timber.w("Maths layout failed (%s)", error.javaClass.simpleName)
            null
        }
        cache.put(key, result ?: Failed)
        return result
    }

    private companion object {
        private object Failed
        @Volatile private var unavailable = false
    }
}
