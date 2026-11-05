/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.maths.api

import com.google.common.truth.Truth.assertThat
import org.jsoup.Jsoup
import org.junit.Test

class MathsElementsTest {
    @Test
    fun `creates inline and display maths with literal fallback and decoded source`() {
        val document = Jsoup.parse("")
        listOf(false, true).forEach { display ->
            val tex = "x < y & z"
            val element = createMathsElement(document, tex, display)
            assertThat(element.isMathsElement()).isTrue()
            assertThat(element.isDisplayMaths()).isEqualTo(display)
            assertThat(element.mathsTex()).isEqualTo(tex)
            assertThat(element.child(0).normalName()).isEqualTo("code")
            assertThat(element.text()).isEqualTo(tex)
            assertThat(Jsoup.parse(element.outerHtml()).selectFirst("[data-mx-maths]")!!.mathsTex()).isEqualTo(tex)
        }
    }

    @Test
    fun `recognises only span and div with attribute`() {
        val document = Jsoup.parse("<p data-mx-maths='x'>x</p><span>x</span><div data-mx-maths=''>x</div>")
        assertThat(document.selectFirst("p")!!.isMathsElement()).isFalse()
        assertThat(document.selectFirst("span")!!.isMathsElement()).isFalse()
        assertThat(document.selectFirst("div")!!.isMathsElement()).isTrue()
    }

    @Test
    fun `fallback keeps children and display block structure`() {
        val document = Jsoup.parse("<span data-mx-maths='x'><b>x</b></span><div data-mx-maths='y'><code>y</code></div>")
        document.select("[data-mx-maths]").forEach { it.replaceMathsWithFallback() }
        assertThat(document.body().html()).isEqualTo("<b>x</b>\n<p><code>y</code></p>")
    }
}
