/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.ui.messages

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.permalink.PermalinkData
import io.element.android.libraries.matrix.api.permalink.PermalinkParser
import io.element.android.libraries.matrix.api.timeline.item.event.FormattedBody
import io.element.android.libraries.matrix.api.timeline.item.event.MessageFormat
import io.element.android.libraries.matrix.test.permalink.FakePermalinkParser
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Test

class ToHtmlDocumentTest : RobolectricTest() {
    @Test
    fun `toHtmlDocument - returns null if format is not HTML`() {
        val body = FormattedBody(
            format = MessageFormat.UNKNOWN,
            body = "Hello world"
        )

        val document = body.toHtmlDocument(permalinkParser = FakePermalinkParser())

        assertThat(document).isNull()
    }

    @Test
    fun `toHtmlDocument - returns a Document if the format is HTML`() {
        val body = FormattedBody(
            format = MessageFormat.HTML,
            body = "<p>Hello world</p>"
        )

        val document = body.toHtmlDocument(permalinkParser = FakePermalinkParser())
        assertThat(document).isNotNull()
        assertThat(document?.text()).isEqualTo("Hello world")
    }

    @Test
    fun `toHtmlDocument - returns a Document with a prefix if provided`() {
        val body = FormattedBody(
            format = MessageFormat.HTML,
            body = "<p>Hello world</p>"
        )

        val document = body.toHtmlDocument(
            permalinkParser = FakePermalinkParser(),
            prefix = "@Jorge:"
        )
        assertThat(document).isNotNull()
        assertThat(document?.text()).isEqualTo("@Jorge: Hello world")
    }

    @Test
    fun `toHtmlDocument - if a mention is found without an '@' prefix, it will be added`() {
        val body = FormattedBody(
            format = MessageFormat.HTML,
            body = "Hey <a href='https://matrix.to/#/@alice:matrix.org'>Alice</a>!"
        )

        val document = body.toHtmlDocument(permalinkParser = object : PermalinkParser {
            override fun parse(uriString: String): PermalinkData {
                return PermalinkData.UserLink(UserId("@alice:matrix.org"))
            }
        })
        assertThat(document?.text()).isEqualTo("Hey @Alice!")
    }

    @Test
    fun `toHtmlDocument - if a mention is found with an '@' prefix, nothing will be done`() {
        val body = FormattedBody(
            format = MessageFormat.HTML,
            body = "Hey <a href='https://matrix.to/#/@alice:matrix.org'>@Alice</a>!"
        )

        val document = body.toHtmlDocument(permalinkParser = object : PermalinkParser {
            override fun parse(uriString: String): PermalinkData {
                return PermalinkData.UserLink(UserId("@alice:matrix.org"))
            }
        })
        assertThat(document?.text()).isEqualTo("Hey @Alice!")
    }

    @Test
    fun `toHtmlDocument - if a link is not a mention, nothing will be done for it`() {
        val body = FormattedBody(
            format = MessageFormat.HTML,
            body = "Hey <a href='https://matrix.org'>Alice</a>!"
        )

        val document = body.toHtmlDocument(permalinkParser = object : PermalinkParser {
            override fun parse(uriString: String): PermalinkData {
                return PermalinkData.FallbackLink(uri = Uri.parse("https://matrix.org"))
            }
        })
        assertThat(document?.text()).isEqualTo("Hey Alice!")
    }

    @Test
    fun `toHtmlDocument - returns null if the body only contains unsupported tags`() {
        val body = FormattedBody(
            format = MessageFormat.HTML,
            body = "<img data-mx-emoticon src=\"mxc://matrix.org/anImage\" height=\"32\" width=\"32\" alt=\"\uD83D\uDE1C\" />"
        )

        val document = body.toHtmlDocument(permalinkParser = FakePermalinkParser())

        assertThat(document).isNull()
    }

    @Test
    fun `toHtmlDocument - returns a Document if some text survives next to unsupported tags`() {
        val body = FormattedBody(
            format = MessageFormat.HTML,
            body = "Look at this <img src=\"mxc://matrix.org/anImage\" alt=\"cat\" />!"
        )

        val document = body.toHtmlDocument(permalinkParser = FakePermalinkParser())

        assertThat(document?.text()).isEqualTo("Look at this !")
    }

    private fun sanitise(html: String, keepMaths: Boolean = true): org.jsoup.nodes.Document? =
        FormattedBody(MessageFormat.HTML, html).toHtmlDocument(FakePermalinkParser(), keepMaths = keepMaths)

    @Test
    fun `maths disabled keeps existing sanitizer output and cannot mutate shared safelist`() {
        val html = "<p>Hello <span class='bad' data-mx-maths='x'><code>x</code></span></p><div data-mx-maths='y'>y</div>"
        val before = sanitise(html, false)!!.body().html()
        assertThat(before).isEqualTo("<p>Hello <code>x</code></p>\ny")
        assertThat(FormattedBody(MessageFormat.HTML, html).toHtmlDocument(FakePermalinkParser())!!.body().html()).isEqualTo(before)
        sanitise(html, true)
        assertThat(sanitise(html, false)!!.body().html()).isEqualTo(before)
    }

    @Test
    fun `keeps spec maths span and display div and strips unsafe attributes`() {
        val document = sanitise(
            "Euler <span onclick='bad()' data-mx-maths='x &lt; y'><code>x</code></span> holds" +
            "<div data-mx-maths='z'><code>z</code></div>"
        )!!
        val maths = document.select("[data-mx-maths]")
        assertThat(maths.map { it.normalName() }).containsExactly("span", "div").inOrder()
        assertThat(maths.first()!!.attr("data-mx-maths")).isEqualTo("x < y")
        assertThat(maths.first()!!.hasAttr("onclick")).isFalse()
        assertThat(document.text()).isEqualTo("Euler x holds z")
    }

    @Test
    fun `unwraps ordinary span div and blank maths attributes`() {
        val document = sanitise("<span>one</span><div>two</div><span data-mx-maths=' '>three</span>")!!
        assertThat(document.select("span, div")).isEmpty()
        assertThat(document.text()).isEqualTo("onetwothree")
    }

    @Test
    fun `unwraps nested maths and maths in pre or code preserving their fallback`() {
        val document = sanitise(
            "<span data-mx-maths='outer'>a<span data-mx-maths='inner'><code>b</code></span></span>" +
            "<pre><span data-mx-maths='x'>c</span></pre><code><span data-mx-maths='y'>d</span></code>"
        )!!
        assertThat(document.select("[data-mx-maths]").map { it.attr("data-mx-maths") }).containsExactly("outer")
        assertThat(document.selectFirst("pre")!!.text()).isEqualTo("c")
        assertThat(document.text()).contains("ab")
        assertThat(document.text()).contains("d")
    }

    @Test
    fun `display maths is inline inside formatting and stays block in permitted parents`() {
        val document = sanitise(
            "<b><div data-mx-maths='x'>x</div></b>" +
            "<blockquote><div data-mx-maths='y'>y</div></blockquote><ul><li><div data-mx-maths='z'>z</div></li></ul>"
        )!!
        assertThat(document.selectFirst("b > span[data-mx-maths]")).isNotNull()
        assertThat(document.select("blockquote > div[data-mx-maths], li > div[data-mx-maths]")).hasSize(2)
    }

    @Test
    fun `synthesises literal code fallback for empty or whitespace only maths`() {
        val document = sanitise("<span data-mx-maths='x &lt; y'></span><div data-mx-maths='z'> </div>")!!
        assertThat(document.select("[data-mx-maths] > code").map { it.text() }).containsExactly("x < y", "z").inOrder()
    }

    @Test
    fun `valid maths survives inside an ordinary span with blank maths attribute`() {
        val document = sanitise("<span data-mx-maths=''><span data-mx-maths='x'>x</span></span>")!!
        assertThat(document.select("[data-mx-maths]").map { it.attr("data-mx-maths") }).containsExactly("x")
    }
}
