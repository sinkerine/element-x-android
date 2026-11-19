/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.factories.event

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.permalink.PermalinkData
import io.element.android.libraries.matrix.api.timeline.item.event.FormattedBody
import io.element.android.libraries.matrix.api.timeline.item.event.MessageFormat
import io.element.android.libraries.matrix.test.permalink.FakePermalinkParser
import io.element.android.libraries.matrix.ui.messages.toHtmlDocument
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Test

class AnswerNavigationParserTest : RobolectricTest() {
    private val permalinkParser = FakePermalinkParser { PermalinkData.FallbackLink(Uri.parse(it)) }
    private val prose = "<p>${"ordinary answer text ".repeat(110)}</p>"
    private val headings = "<h2>Summary</h2><p>First passage</p><h2>Evidence</h2><p>Second passage</p><h2>Evidence</h2><p>Third passage</p>"

    private fun parse(html: String, length: Int = 2_000) = AnswerNavigationParser.parse(prose + html, length) {
        FormattedBody(MessageFormat.HTML, it).toHtmlDocument(permalinkParser)?.text().orEmpty()
    }

    @Test
    fun `requires three nonempty headings and two thousand rendered characters`() {
        assertThat(parse(headings, 1_999)).isNull()
        assertThat(parse("<h2>One</h2><h2>Two</h2>$prose")).isNull()
        assertThat(parse("<h2>One</h2><h2>Two</h2><h2> </h2>$prose")).isNull()
        assertThat(parse(prose)).isNull()
        assertThat(parse(headings, 2_000)).isNotNull()
    }

    @Test
    fun `quoted and hidden prose cannot make a short answer eligible`() {
        val html = "<blockquote>$prose</blockquote><span data-mx-spoiler>$prose</span>$headings"
        assertThat(AnswerNavigationParser.parse(html, 5_000) { fragment ->
            FormattedBody(MessageFormat.HTML, fragment).toHtmlDocument(permalinkParser)?.text().orEmpty()
        }).isNull()
    }

    @Test
    fun `preserves introduction and repeated headings as distinct ordered passages`() {
        val navigation = parse("<p>Introduction</p>$headings")!!
        assertThat(navigation.introduction.toString()).endsWith("Introduction")
        assertThat(navigation.sections.map { it.title }).containsExactly("Summary", "Evidence", "Evidence").inOrder()
        assertThat(navigation.sections.map { it.body.toString() }).containsExactly("First passage", "Second passage", "Third passage").inOrder()
    }

    @Test
    fun `keeps formatted heading labels and levels without trusting IDs`() {
        val navigation = parse("<h1 id='unsafe' onclick='bad()'><strong>Summary</strong></h1><h3>Evidence</h3><h6>Details</h6>")!!
        assertThat(navigation.sections.map { it.level }).containsExactly(1, 3, 6).inOrder()
        assertThat(navigation.sections.first().heading.toString()).isEqualTo("Summary")
    }

    @Test
    fun `excludes quotes replies code and spoilers including nested label spoilers`() {
        val excluded = "<blockquote><h2>Quoted</h2></blockquote><mx-reply><h2>Reply</h2></mx-reply>" +
            "<pre><code>&lt;h2&gt;Code&lt;/h2&gt;</code></pre><span data-mx-spoiler><h2>Hidden</h2></span>" +
            "<h2>Visible <span data-mx-spoiler>hidden title</span></h2>"
        val navigation = parse(excluded + headings)!!
        assertThat(navigation.sections.map { it.title }).containsExactly("Summary", "Evidence", "Evidence").inOrder()
        assertThat(parse(excluded)).isNull()
    }

    @Test
    fun `does not promote headings nested in lists into independent sections`() {
        val navigation = parse("<ul><li><h2>Nested material</h2></li></ul>$headings")!!
        assertThat(navigation.sections).hasSize(3)
        assertThat(navigation.introduction.toString()).contains("Nested material")
    }

    @Test
    fun `only explicit sources or references supply sources and author labels`() {
        val navigation = parse(
            "$headings<a href='https://ordinary.example'>Ordinary link</a>" +
                "<h2>rEfErEnCeS</h2><ul><li><a href='https://EXAMPLE.org/paper'>Author supplied <b>label</b></a></li></ul>"
        )!!
        assertThat(navigation.sources.map { it.url }).containsExactly("https://EXAMPLE.org/paper")
        assertThat(navigation.sources.single().label).isEqualTo("Author supplied label")
        assertThat(navigation.sources.single().host).isEqualTo("example.org")
    }

    @Test
    fun `source sections include deeper subsections and end at same or higher heading`() {
        val navigation = parse(
            "$headings<h2>Sources</h2><a href='https://a.example'>A</a>" +
                "<h3>Extra</h3><a href='https://b.example'>B</a>" +
                "<h2>Other</h2><a href='https://not-source.example'>C</a>" +
                "<h3>References</h3><a href='https://d.example'>D</a>" +
                "<h1>End</h1><a href='https://also-not.example'>E</a>"
        )!!
        assertThat(navigation.sources.map { it.label }).containsExactly("A", "B", "D").inOrder()
    }

    @Test
    fun `a heading with a spoiler still ends the preceding sources section`() {
        val navigation = parse(
            "$headings<h2>Sources</h2><a href='https://a.example'>A</a>" +
                "<h2>Other <span data-mx-spoiler>hidden</span></h2><a href='https://b.example'>B</a>"
        )!!
        assertThat(navigation.sources.map { it.label }).containsExactly("A")
    }

    @Test
    fun `nested references do not shorten the enclosing sources section`() {
        val navigation = parse(
            "$headings<h2>Sources</h2><h3>References</h3><a href='https://a.example'>A</a>" +
                "<h3>Additional reading</h3><a href='https://b.example'>B</a><h2>End</h2><a href='https://c.example'>C</a>"
        )!!
        assertThat(navigation.sources.map { it.label }).containsExactly("A", "B").inOrder()
    }

    @Test
    fun `deduplicates destinations retaining the first author label`() {
        val navigation = parse("$headings<h2>Sources</h2><a href='https://a.example'>First</a><a href='https://a.example'>Second</a>")!!
        assertThat(navigation.sources).hasSize(1)
        assertThat(navigation.sources.single().label).isEqualTo("First")
    }

    @Test
    fun `source links exclude quoted replied code and spoiler content or labels`() {
        val navigation = parse(
            "$headings<h2>Sources</h2><blockquote><a href='https://a.example'>Quote</a></blockquote>" +
                "<mx-reply><a href='https://b.example'>Reply</a></mx-reply><pre><a href='https://c.example'>Code</a></pre>" +
                "<span data-mx-spoiler><a href='https://d.example'>Hidden</a></span>" +
                "<a href='https://e.example'>Visible <span data-mx-spoiler>hidden label</span></a>" +
                "<a href='https://visible.example'>Visible</a>"
        )!!
        assertThat(navigation.sources.map { it.label }).containsExactly("Visible")
    }

    @Test
    fun `accepts only absolute http and https sources with a host and visible label`() {
        val navigation = parse(
            "$headings<h2>Sources</h2><a href='javascript:alert(1)'>JS</a><a href='file:///secret'>File</a>" +
                "<a href='/relative'>Relative</a><a href='mailto:a@example.org'>Mail</a><a href='https://a.example'></a>" +
                "<a href='https://'>Broken</a><a href='https://a.example/a?b=1&amp;c=2'>HTTPS</a><a href='http://b.example'>HTTP</a>"
        )!!
        assertThat(navigation.sources.map { it.label }).containsExactly("HTTPS", "HTTP").inOrder()
        assertThat(navigation.sources.first().url).isEqualTo("https://a.example/a?b=1&c=2")
    }

    @Test
    fun `source-free answers have no sources and near matching headings do not imply citations`() {
        assertThat(parse(headings)!!.sources).isEmpty()
        assertThat(parse("$headings<h2>Further sources</h2><a href='https://a.example'>A</a>")!!.sources).isEmpty()
    }

    @Test
    fun `edits rebuild destinations labels and section bodies`() {
        val original = "$headings<h2>Sources</h2><a href='https://a.example'>A</a>"
        val before = parse(original)!!
        val after = parse(original.replace("https://a.example", "https://b.example").replace("Third passage", "Edited passage"))!!
        assertThat(before.sources.single().url).isEqualTo("https://a.example")
        assertThat(after.sources.single().url).isEqualTo("https://b.example")
        assertThat(after.sections[2].body.toString()).isEqualTo("Edited passage")
        assertThat(parse(original, 1_000)).isNull()
    }

    @Test
    fun `all display fragments pass through existing sanitizer`() {
        val fragments = mutableListOf<String>()
        val html = "$prose<p id='private' onclick='ignored()'>Intro</p>$headings<h2>Sources</h2><a href='https://a.example'>A</a>"
        AnswerNavigationParser.parse(html, 2_000) {
            val sanitized = FormattedBody(MessageFormat.HTML, it).toHtmlDocument(permalinkParser)?.body()?.html().orEmpty()
            fragments.add(sanitized)
            sanitized
        }
        assertThat(fragments).isNotEmpty()
        assertThat(fragments.joinToString()).doesNotContain("onclick=")
        assertThat(fragments.joinToString()).doesNotContain("id=")
    }
}
