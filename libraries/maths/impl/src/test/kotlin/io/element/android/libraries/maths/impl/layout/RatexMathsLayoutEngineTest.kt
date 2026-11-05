/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.maths.impl.layout

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.ratex.DisplayItem
import io.ratex.DisplayList
import io.ratex.RaTeXColor
import io.ratex.RaTeXFontLoader
import org.junit.Test
import timber.log.Timber

class RatexMathsLayoutEngineTest : RobolectricTest() {
    @Test
    fun `unavailable native engine returns null without retry or content in logs`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val messages = mutableListOf<String>()
        val tree = object : Timber.DebugTree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                assertThat(t).isNull()
                messages += message
            }
        }
        mockkStatic(RaTeXFontLoader::class)
        every { RaTeXFontLoader.ensureLoaded(any(), any()) } returns 0
        Timber.plant(tree)
        try {
            val engine = RatexMathsLayoutEngine(context)
            assertThat(engine.layout("private source", false)).isNull()
            assertThat(engine.layout("another source", true)).isNull()
            assertThat(RatexMathsLayoutEngine(context).layout("third source", false)).isNull()
            verify(exactly = 1) { RaTeXFontLoader.ensureLoaded(any(), any()) }
            assertThat(messages).hasSize(1)
            assertThat(messages.single()).contains("Maths engine unavailable")
            assertThat(messages.single()).doesNotContain("source")
        } finally {
            Timber.uproot(tree)
            unmockkStatic(RaTeXFontLoader::class)
        }
    }

    @Test
    fun `layout limits accept boundaries and reject excessive non finite or negative dimensions`() {
        val valid = DisplayList(500.0, 75.0, 25.0, emptyList())
        assertThat(valid.isWithinLimits()).isTrue()
        listOf(
            valid.copy(width = 500.01),
            valid.copy(height = 75.01),
            valid.copy(depth = 25.01),
            valid.copy(width = Double.NaN),
            valid.copy(height = Double.POSITIVE_INFINITY),
            valid.copy(depth = Double.NaN),
            valid.copy(width = -1.0),
            valid.copy(height = -1.0),
            valid.copy(depth = -1.0),
        ).forEach { assertThat(it.isWithinLimits()).isFalse() }
        val item = DisplayItem.Rect(0.0, 0.0, 1.0, 1.0, RaTeXColor.Black)
        assertThat(valid.copy(items = List(20_000) { item }).isWithinLimits()).isTrue()
        assertThat(valid.copy(items = List(20_001) { item }).isWithinLimits()).isFalse()
    }
}
