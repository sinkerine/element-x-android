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
import io.ratex.RaTeXFontLoader
import org.junit.Test
import timber.log.Timber

class DefaultMathsLayoutEngineCacheTest : RobolectricTest() {
    @Test
    fun `caches failures by source and display style and evicts least recently used entries`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val messages = mutableListOf<String>()
        val tree = object : Timber.DebugTree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                assertThat(t).isNull()
                messages += message
            }
        }
        mockkStatic(RaTeXFontLoader::class)
        every { RaTeXFontLoader.ensureLoaded(any(), any()) } throws IllegalStateException("private source")
        Timber.plant(tree)
        try {
            val engine = DefaultMathsLayoutEngine(context)
            assertThat(engine.layout(" ", false)).isNull()
            assertThat(engine.layout("x".repeat(2_001), false)).isNull()
            verify(exactly = 0) { RaTeXFontLoader.ensureLoaded(any(), any()) }
            assertThat(engine.layout("x", false)).isNull()
            assertThat(engine.layout("x", false)).isNull()
            verify(exactly = 1) { RaTeXFontLoader.ensureLoaded(any(), any()) }
            assertThat(engine.layout("x", true)).isNull()
            verify(exactly = 2) { RaTeXFontLoader.ensureLoaded(any(), any()) }
            repeat(254) { assertThat(engine.layout("y$it", false)).isNull() }
            assertThat(engine.layout("x", false)).isNull() // Touch inline x; display x is now oldest.
            assertThat(engine.layout("extra", false)).isNull()
            assertThat(engine.layout("x", false)).isNull()
            verify(exactly = 257) { RaTeXFontLoader.ensureLoaded(any(), any()) }
            assertThat(engine.layout("x", true)).isNull()
            verify(exactly = 258) { RaTeXFontLoader.ensureLoaded(any(), any()) }
            assertThat(messages).hasSize(258)
            assertThat(messages.toSet()).containsExactly("Maths layout failed (IllegalStateException)")
        } finally {
            Timber.uproot(tree)
            unmockkStatic(RaTeXFontLoader::class)
        }
    }
}
