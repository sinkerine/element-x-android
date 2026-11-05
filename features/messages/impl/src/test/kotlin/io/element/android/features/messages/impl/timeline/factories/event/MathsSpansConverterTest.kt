/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.factories.event

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import androidx.core.text.getSpans
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.maths.api.MathsSpan
import io.element.android.libraries.maths.api.withMathsMaxWidth
import io.element.android.libraries.maths.test.FakeMathsLayout
import io.element.android.libraries.maths.test.FakeMathsLayoutEngine
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.wysiwyg.view.spans.OrderedListSpan
import io.element.android.wysiwyg.view.spans.QuoteSpan
import io.element.android.wysiwyg.view.spans.UnorderedListSpan
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import org.junit.Test
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MathsSpansConverterTest : RobolectricTest() {
    @Test
    fun `inline and display spans preserve positions styling and input`() {
        val document = Jsoup.parse("before <span data-mx-maths='x'><code>x</code></span><div data-mx-maths='y'>y</div> after")
        val original = document.outerHtml()
        val engine = FakeMathsLayoutEngine()
        val style = StyleSpan(1)
        val result = MathsSpansConverter(engine).convert(document) {
            assertThat(it.select("p").single().text()).isEqualTo("\uFFFC")
            android.text.SpannableString(it.text()).apply { setSpan(style, 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        } as Spanned
        val spans = result.getSpans<MathsSpan>()
        assertThat(spans.map { it.tex to it.displayMode }).containsExactly("x" to false, "y" to true).inOrder()
        spans.forEach { span ->
            assertThat(result[result.getSpanStart(span)]).isEqualTo('\uFFFC')
            assertThat(result.getSpanEnd(span)).isEqualTo(result.getSpanStart(span) + 1)
        }
        assertThat(result.getSpans<StyleSpan>().single()).isSameInstanceAs(style)
        assertThat(document.outerHtml()).isEqualTo(original)
    }

    @Test
    fun `null layouts preserve sender and detected literal fallbacks`() {
        val document = Jsoup.parse("<span data-mx-maths='x'><code>sender fallback</code></span><div data-mx-maths='y'></div>")
        document.select("div").single().appendChild(TextNode("\$\$y\$\$"))
        val original = document.outerHtml()
        val result = MathsSpansConverter(FakeMathsLayoutEngine { _, _ -> null }).convert(document) { it.text() }
        assertThat(result.toString()).isEqualTo("sender fallback \$\$y\$\$")
        assertThat(document.outerHtml()).isEqualTo(original)
    }

    @Test
    fun `element cap skips layout and preserves remaining fallbacks`() {
        val document = Jsoup.parse((0..50).joinToString("") { "<span data-mx-maths='$it'><code>fallback$it</code></span>" })
        val engine = FakeMathsLayoutEngine()
        val result = MathsSpansConverter(engine).convert(document) { it.text() } as Spanned
        assertThat(engine.calls).hasSize(50)
        assertThat(result.getSpans<MathsSpan>()).hasLength(50)
        assertThat(result.toString()).endsWith("fallback50")
    }

    @Test
    fun `existing marker bypasses layout and preserves fallback and input`() {
        val document = Jsoup.parse("\uFFFC <span data-mx-maths='x'><code>fallback</code></span>")
        val original = document.outerHtml()
        val engine = FakeMathsLayoutEngine()
        val result = MathsSpansConverter(engine).convert(document) { it.text() }
        assertThat(result.toString()).isEqualTo("\uFFFC fallback")
        assertThat(engine.calls).isEmpty()
        assertThat(document.outerHtml()).isEqualTo(original)
    }

    @Test
    fun `missing or extra markers trigger a fresh full fallback conversion`() {
        for (replacement in listOf("", "\uFFFC\uFFFC")) {
            val document = Jsoup.parse("<span data-mx-maths='x'><code>fallback</code></span>")
            val original = document.outerHtml()
            var conversions = 0
            val result = MathsSpansConverter(FakeMathsLayoutEngine()).convert(document) {
                conversions++
                if (conversions == 1) replacement else it.text()
            }
            assertThat(conversions).isEqualTo(2)
            assertThat(result.toString()).isEqualTo("fallback")
            assertThat(document.outerHtml()).isEqualTo(original)
        }
    }

    @Test
    fun `actual legacy quote and list spans leave wide maths inside the static layout width`() {
        val margins = listOf(
            listOf(QuoteSpan(Color.BLACK, 4, 8, 8)),
            listOf(UnorderedListSpan(16, 4, 0)),
            listOf(OrderedListSpan(Typeface.DEFAULT, 20f, 1, 16)),
            listOf(QuoteSpan(Color.BLACK, 4, 8, 8), UnorderedListSpan(16, 4, 1), UnorderedListSpan(16, 4, 2)),
        )
        for (paragraphMargins in margins) {
            val formula = FakeMathsLayout(widthEm = 20f)
            val text = MathsSpansConverter(FakeMathsLayoutEngine { _, _ -> formula }).convert(
                Jsoup.parse("<span data-mx-maths='x'>x</span>"),
            ) { document ->
                android.text.SpannableString(document.text()).apply {
                    paragraphMargins.forEach { setSpan(it, 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                }
            }
            val paint = TextPaint().apply {
                textSize = 20f
                color = Color.RED
            }
            val unfitted = StaticLayout.Builder.obtain(text, 0, text.length, paint, 300).build()
            assertThat(unfitted.getLineRight(0)).isGreaterThan(300f)
            val fitted = text.withMathsMaxWidth(300) as Spanned
            val expectedMargin = paragraphMargins.sumOf { (it as LeadingMarginSpan).getLeadingMargin(true) }
            assertThat(fitted.getSpans<MathsSpan>().single().maxWidthPx).isEqualTo(300 - expectedMargin)
            val layout = StaticLayout.Builder.obtain(fitted, 0, fitted.length, paint, 300).build()
            assertThat(layout.getPrimaryHorizontal(0)).isEqualTo(expectedMargin.toFloat())
            assertThat(layout.getLineRight(0)).isAtMost(300f)
            val bitmap = Bitmap.createBitmap(500, layout.height, Bitmap.Config.ARGB_8888)
            layout.draw(Canvas(bitmap))
            assertThat(formula.drawCalls.single().first / paint.textSize).isGreaterThan(MathsSpan.MIN_FIT_SCALE)
            assertThat((300 until bitmap.width).any { x ->
                (0 until bitmap.height).any { y -> bitmap.getPixel(x, y) == Color.RED }
            }).isFalse()
        }
    }

    @Test
    fun `ordinary document goes directly to conversion without cloning or calling layout`() {
        val document = Jsoup.parse("ordinary")
        val engine = FakeMathsLayoutEngine()
        val result = MathsSpansConverter(engine).convert(document) {
            assertThat(it).isSameInstanceAs(document)
            it.text()
        }
        assertThat(result.toString()).isEqualTo("ordinary")
        assertThat(engine.calls).isEmpty()
    }
}
