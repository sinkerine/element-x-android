/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline

import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TimelineTextAnchorTest : RobolectricTest() {
    private fun layout(text: String): Layout = StaticLayout.Builder.obtain(text, 0, text.length, TextPaint().apply { textSize = 20f }, 400).build()

    @Test
    fun `append replaces layout while retaining an earlier reading line`() {
        val before = layout("Earlier\nReading passage\nLater")
        val anchor = TimelineTextAnchor.capture(before, before.getLineTop(1))!!
        assertEquals(before.getLineTop(1), anchor.resolve(layout("Earlier\nReading passage\nLater\nContinued")))
    }

    @Test
    fun `unique passage recovers after text is inserted above it`() {
        val before = layout("Earlier\nReading passage\nLater")
        val anchor = TimelineTextAnchor.capture(before, before.getLineTop(1))!!
        val after = layout("Earlier\nInserted\nReading passage\nLater")
        assertEquals(after.getLineTop(2), anchor.resolve(after))
    }

    @Test
    fun `rewritten removed and ambiguous passages fall back to the row`() {
        val before = layout("Earlier\nReading passage\nLater")
        val anchor = TimelineTextAnchor.capture(before, before.getLineTop(1))!!
        assertNull(anchor.resolve(layout("Earlier\nRewritten\nLater")))
        assertNull(anchor.resolve(layout("Earlier\nLater")))
        assertNull(anchor.resolve(layout("Inserted\nMore\nReading passage\nReading passage\nLater")))
    }

    @Test
    fun `first complete visible line wins over a clipped line`() {
        val before = layout("Earlier\nReading passage\nLater")
        assertEquals(before.getLineTop(1), TimelineTextAnchor.capture(before, 1)!!.lineTop)
    }

    @Test
    fun `blank separator lines do not discard the visible passage`() {
        val before = layout("Earlier\n\nReading passage\nLater")
        val anchor = TimelineTextAnchor.capture(before, before.getLineTop(1))!!
        assertEquals(before.getLineTop(2), anchor.lineTop)
        val after = layout("Earlier\nInserted\n\nReading passage\nLater")
        assertEquals(after.getLineTop(3), anchor.resolve(after))
    }

    @Test
    fun `empty or blank text has no text anchor`() {
        assertNull(TimelineTextAnchor.capture(layout(""), 0))
        assertNull(TimelineTextAnchor.capture(layout("\n"), 0))
    }
}
