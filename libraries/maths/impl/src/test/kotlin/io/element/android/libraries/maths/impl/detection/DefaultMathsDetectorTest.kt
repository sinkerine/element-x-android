/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.maths.impl.detection

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.maths.api.MATHS_ATTRIBUTE
import io.element.android.libraries.maths.api.mathsTex
import io.element.android.libraries.maths.api.replaceMathsWithFallback
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.TextNode
import org.junit.Test

class DefaultMathsDetectorTest {
    private val detector = DefaultMathsDetector()

    @Test
    fun `acceptance cases for plain messages`() {
        val cases = listOf(
            """Area $\pi r^2$""" to listOf("""\pi r^2"""),
            "Identity (\$a+b\$) holds" to listOf("a+b"),
            """Energy ${'$'}E=mc^2$ and \(a\,b\)

\[\frac{1}{2}\]""".trimIndent() to listOf("E=mc^2", """a\,b""", """\frac{1}{2}"""),
            """$$\sum_k k$$""" to listOf("""\sum_k k"""),
        )
        cases.forEach { (body, expected) ->
            assertThat(detector.detect(body, null)!!.select("[$MATHS_ATTRIBUTE]").map { it.mathsTex() })
                .containsExactlyElementsIn(expected)
                .inOrder()
        }
    }

    @Test
    fun `rejects currency escaped dollars and code in plain and HTML messages`() {
        listOf("Tickets are \$5/\$10", "\$5-\$10", "\$3,\$4", "I paid \$5 and \$10").forEach { body ->
            assertThat(detector.detect(body, null)).isNull()
            assertThat(detector.detect(body, Jsoup.parse(body))).isNull()
        }
        assertThat(detector.detect("""\${'$'}x\$""", Jsoup.parse("\$x\$"))).isNull()
        assertThat(detector.detect("Use `\$x\$`", Jsoup.parse("Use <code>\$x\$</code>"))).isNull()
        assertThat(detector.detect("see [link \$x\$](https://e.org)", Jsoup.parse("<a href='https://e.org'>link \$x\$</a>"))).isNull()
    }

    @Test
    fun `CommonMark mapping preserves faithful raw TeX and source document`() {
        val body = """Energy ${'$'}E=mc^2$ and \(a\,b\)

\[\frac{1}{2}\]""".trimIndent()
        val original = Jsoup.parse("""<p>Energy ${'$'}E=mc^2$ and (a,b)</p><p>[\frac{1}{2}]</p>""")
        val before = original.outerHtml()
        val detected = detector.detect(body, original)!!
        val maths = detected.select("[$MATHS_ATTRIBUTE]")
        assertThat(maths.map { it.mathsTex() }).containsExactly("E=mc^2", """a\,b""", """\frac{1}{2}""").inOrder()
        assertThat(maths.map { it.normalName() }).containsExactly("span", "span", "div").inOrder()
        maths.forEach { it.replaceMathsWithFallback() }
        assertThat(detected.outerHtml()).isEqualTo(before)
        assertThat(original.outerHtml()).isEqualTo(before)
    }

    @Test
    fun `fallback retains raw delimiters without code styling in plain mode`() {
        val body = """bad $\frac{$ and \(\def\x{x}\)"""
        val result = detector.detect(body, null)!!
        result.select("[$MATHS_ATTRIBUTE]").forEach {
            assertThat(it.childNode(0)).isInstanceOf(TextNode::class.java)
            it.replaceMathsWithFallback()
        }
        assertThat(result.body().wholeText()).isEqualTo(body)
        assertThat(result.select("code")).isEmpty()
    }

    @Test
    fun `HTML fallback retains actual matched whitespace and visible CommonMark output`() {
        val original = Jsoup.parse("<b>(a,  b)</b> and \$x\$")
        val result = detector.detect("""\(a\, b\) and ${'$'}x$""", original)!!
        result.select("[$MATHS_ATTRIBUTE]").forEach { it.replaceMathsWithFallback() }
        assertThat(result.outerHtml()).isEqualTo(original.outerHtml())
    }

    @Test
    fun `plain mode escapes HTML and builds line breaks without redundant breaks at display maths`() {
        val result = detector.detect("<b>&\n\$x\$\n\$\$y\$\$\na\n\nb", null)!!
        result.outputSettings().prettyPrint(false)
        assertThat(result.body().html()).isEqualTo(
            "&lt;b&gt;&amp;<br><span data-mx-maths=\"x\">\$x\$</span><div data-mx-maths=\"y\">\$\$y\$\$</div>a<br><br>b"
        )
    }

    @Test
    fun `display maths becomes div only for whole paragraphs or supported text runs`() {
        val body = """\[x\]"""
        listOf("<p>[x]</p>", "[x]", "<blockquote>[x]</blockquote>", "<ul><li>[x]</li></ul>").forEach { html ->
            assertThat(detector.detect(body, Jsoup.parse(html))!!.selectFirst("[$MATHS_ATTRIBUTE]")!!.normalName()).isEqualTo("div")
        }
        listOf("<p>before [x]</p>", "<p>[x]<b>after</b></p>", "<b>[x]</b>", "<p><i>[x]</i></p>").forEach { html ->
            assertThat(detector.detect(body, Jsoup.parse(html))!!.selectFirst("[$MATHS_ATTRIBUTE]")!!.normalName()).isEqualTo("span")
        }
        val result = detector.detect(body, Jsoup.parse("<p>  [x] \n</p>"))!!
        assertThat(result.selectFirst("div")!!.wholeText()).isEqualTo("  [x] \n")
    }

    @Test
    fun `skips unmatched needles and never crosses text nodes`() {
        assertThat(detector.detect("\$a*b*c\$", Jsoup.parse("<p>\$a<em>b</em>c\$</p>"))).isNull()
        val result = detector.detect("\$missing\$ and \$x\$", Jsoup.parse("<p>\$x\$</p>"))!!
        assertThat(result.select("[$MATHS_ATTRIBUTE]").map { it.mathsTex() }).containsExactly("x")
    }

    @Test
    fun `repeated text is mapped in document order and cursor advances within a text node`() {
        val original = Jsoup.parse("<p>(a,b) then (a,b)</p><p>(a,b)</p>")
        val result = detector.detect("""\(a\,b\) then \(a,b\) then \(a\,b\)""", original)!!
        assertThat(result.select("[$MATHS_ATTRIBUTE]").map { it.mathsTex() }).containsExactly("""a\,b""", "a,b", """a\,b""").inOrder()
        assertThat(original.select("[$MATHS_ATTRIBUTE]")).isEmpty()
    }

    @Test
    fun `excludes protected subtrees and honours maths aware senders`() {
        listOf("code", "pre", "a", "mx-reply").forEach { tag ->
            val document = Jsoup.parse("<$tag><b>\$x\$</b></$tag><p>\$y\$</p>")
            val result = detector.detect("\$x\$ and \$y\$", document)!!
            assertThat(result.select("[$MATHS_ATTRIBUTE]").map { it.mathsTex() }).containsExactly("y")
        }
        assertThat(detector.detect("\$x\$", Jsoup.parse("<span data-mx-maths='y'>y</span>\$x\$"))).isNull()
        assertThat(detector.detect("\$x\$", Jsoup.parse("<div data-mx-maths='y'>y</div>\$x\$"))).isNull()
    }

    @Test
    fun `quick reject does not inspect or clone the document`() {
        val document = object : Document("") {
            override fun clone(): Document = error("Must not clone")
        }
        assertThat(detector.detect("ordinary \\alpha [text] (text)", document)).isNull()
    }

    @Test
    fun `unexpected document failure returns null`() {
        val document = object : Document("") {
            override fun clone(): Document = error("Synthetic failure")
        }
        assertThat(detector.detect("\$x\$", document)).isNull()
    }

    @Test
    fun `JVM stack exhaustion falls back without throwing`() {
        val document = object : Document("") {
            override fun clone(): Document = throw StackOverflowError()
        }
        assertThat(detector.detect("\$x\$", document)).isNull()
    }

    @Test(timeout = 10_000)
    fun `long HTML needles fail safely without changing the original document`() {
        val tex = "a ".repeat(10_000) + "b"
        val body = "\\($tex\\)"
        val document = Jsoup.parse("<p>($tex)</p>")
        val original = document.outerHtml()
        val detected = detector.detect(body, document)
        detected?.select("[$MATHS_ATTRIBUTE]")?.forEach { it.replaceMathsWithFallback() }
        assertThat(detected?.outerHtml() ?: document.outerHtml()).isEqualTo(original)
        assertThat(detector.detect(body, Jsoup.parse("<p>($tex missing)</p>"))).isNull()
        assertThat(document.outerHtml()).isEqualTo(original)
    }

    @Test
    fun `literal lookalike before or after maths makes HTML mapping conservative`() {
        listOf(
            """literal (a,b) then \(a\,b\)""",
            """\(a\,b\) then literal (a,b)""",
        ).forEach { body ->
            val document = Jsoup.parse("<p>literal (a,b) then (a,b)</p>")
            val before = document.outerHtml()
            assertThat(detector.detect(body, document)).isNull()
            assertThat(document.outerHtml()).isEqualTo(before)
        }
    }

    @Test(timeout = 10_000)
    fun `repeated HTML prefixes exhaust a deterministic budget and preserve original text`() {
        val body = "\\( " + "( ".repeat(10_000) + "z\\)"
        val document = Jsoup.parse("<p>" + "( ".repeat(100_000) + "</p>")
        val before = document.outerHtml()
        assertThat(detector.detect(body, document)).isNull()
        assertThat(document.outerHtml()).isEqualTo(before)
    }

    @Test
    fun `plain mode respects fences escaped backticks and links`() {
        listOf(
            "```\ntext ``` \$x\$",
            "   ```\n\$x\$",
            "see [link \$x\$](https://e.org)",
            "see [link \$x\$](https://e.org/a(b))",
            "see [link \$x\$][target]",
        ).forEach { assertThat(detector.detect(it, null)).isNull() }
        listOf("```\n\$x\$\n````\n\$y\$", "\\` \$y\$ \\`").forEach { body ->
            assertThat(detector.detect(body, null)!!.select("[$MATHS_ATTRIBUTE]").map { it.mathsTex() }).containsExactly("y")
        }
    }

    @Test
    fun `inline code in link labels remains opaque without losing enclosing brackets`() {
        listOf(
            "see [link `code` \$x\$](https://e.org)",
            "see [link `] ( [` \$x\$](https://e.org)",
            "see [link ``] ` [`` \$x\$][target]",
            "see [link \\``] [` \$x\$](https://e.org)",
        ).forEach { body ->
            assertThat(detector.detect(body, null)).isNull()
            assertThat(detector.detect("$body then \$y\$", null)!!.select("[$MATHS_ATTRIBUTE]").map { it.mathsTex() }).containsExactly("y")
        }
    }

    @Test
    fun `escaped first backtick leaves the rest of its run available as a code opener`() {
        listOf("\\`` \$x\$ `", "\\``` \$x\$ ``", "` \$x\$ \\`").forEach {
            assertThat(detector.detect(it, null)).isNull()
        }
        assertThat(detector.detect("\\` \$x\$ \\`", null)!!.selectFirst("[$MATHS_ATTRIBUTE]")!!.mathsTex()).isEqualTo("x")
    }

    @Test
    fun `entity lookalikes cannot steal a later maths occurrence`() {
        listOf("&#40;a,b&#41;", "&#x28;a,b&#x29;", "&lpar;a,b&rpar;", "\\&#40;a,b&#41;").forEach { literal ->
            val body = "literal $literal then \\(a\\,b\\)"
            val document = Jsoup.parse("<p>literal (a,b) then (a,b)</p>")
            val before = document.outerHtml()
            assertThat(detector.detect(body, document)).isNull()
            assertThat(document.outerHtml()).isEqualTo(before)
        }
        // Entity-produced backslashes are literal; they must not be unescaped a second time.
        val document = Jsoup.parse("<p>literal \\(a,b\\) then (a,b)</p>")
        val result = detector.detect("literal &#92;(a,b&#92;) then \\(a\\,b\\)", document)!!
        assertThat(result.selectFirst("p")!!.childNode(0).toString()).isEqualTo("literal \\(a,b\\) then ")
        assertThat(result.selectFirst("[$MATHS_ATTRIBUTE]")!!.mathsTex()).isEqualTo("a\\,b")
    }

    @Test
    fun `entity decoded dollar lookalikes before and after formulas stay literal`() {
        listOf(
            "literal &#36;x&#36; then \$x\$",
            "\$x\$ then literal &dollar;x&dollar;",
        ).forEach { body ->
            val document = Jsoup.parse("<p>literal \$x\$ then \$x\$</p>")
            assertThat(detector.detect(body, document)).isNull()
        }
    }

    @Test
    fun `identical overflow formulas are maths ranges rather than literal ambiguity gaps`() {
        val body = List(51) { "\$x\$" }.joinToString(" ")
        val original = Jsoup.parse("<p>$body</p>")
        val result = detector.detect(body, original)!!
        assertThat(result.select("[$MATHS_ATTRIBUTE]")).hasSize(50)
        assertThat((result.selectFirst("p")!!.childNodes().last() as TextNode).wholeText).isEqualTo(" \$x\$")
        result.select("[$MATHS_ATTRIBUTE]").forEach { it.replaceMathsWithFallback() }
        assertThat(result.outerHtml()).isEqualTo(original.outerHtml())
    }

    @Test
    fun `restructured formula cannot steal a later whitespace equivalent occurrence`() {
        val body = "\\(a\\,  \nb\\) then \\(a, b\\)"
        val document = Jsoup.parse("<p>(a,<br>\nb) then (a, b)</p>")
        val original = document.outerHtml()
        assertThat(detector.detect(body, document)).isNull()
        assertThat(document.outerHtml()).isEqualTo(original)
        // An unrelated formula still maps, while the entire collision group stays literal.
        val result = detector.detect("$body and \$z\$", Jsoup.parse("<p>(a,<br>\nb) then (a, b) and \$z\$</p>"))!!
        assertThat(result.select("[$MATHS_ATTRIBUTE]").map { it.mathsTex() }).containsExactly("z")
    }

    @Test
    fun `complete whitespace equivalent occurrences retain their own raw TeX in order`() {
        val body = "\\(a\\,  \nb\\) then \\(a, b\\)"
        val document = Jsoup.parse("<p>(a,  \nb) then (a, b)</p>")
        val result = detector.detect(body, document)!!
        assertThat(result.select("[$MATHS_ATTRIBUTE]").map { it.mathsTex() }).containsExactly("a\\,  \nb", "a, b").inOrder()
        result.select("[$MATHS_ATTRIBUTE]").forEach { it.replaceMathsWithFallback() }
        assertThat(result.outerHtml()).isEqualTo(document.outerHtml())
    }

    @Test
    fun `protected nodes do not count toward colliding needle correspondence`() {
        val body = "\\(a\\, b\\) then \\(a, b\\)"
        listOf("code", "pre", "a", "mx-reply").forEach { tag ->
            val document = Jsoup.parse("<$tag>(a, b)</$tag><p>(a, b)</p>")
            assertThat(detector.detect(body, document)).isNull()
        }
    }

    @Test(timeout = 10_000)
    fun `colliding occurrence accounting shares the HTML work budget`() {
        val needle = "( ".repeat(10_000) + "z"
        val body = "\\($needle\\) then \\($needle\\)"
        val document = Jsoup.parse("<p>" + "( ".repeat(100_000) + "</p>")
        val original = document.outerHtml()
        assertThat(detector.detect(body, document)).isNull()
        assertThat(document.outerHtml()).isEqualTo(original)
    }

    @Test
    fun `restructured formula cannot consume a substring of another formula`() {
        val body = "\\(a\\,  \nb\\) then \\(c(a, b)d\\)"
        val document = Jsoup.parse("<p>(a,<br>\nb) then (c(a, b)d)</p>")
        val before = document.outerHtml()
        assertThat(detector.detect(body, document)).isNull()
        assertThat(document.outerHtml()).isEqualTo(before)
    }

    @Test
    fun `contained and partially overlapping candidates stay literal in either raw order`() {
        val cases = listOf(
            "\\(a,b\\) then \\(c(a,b)d\\)" to "(c(a,b)d)",
            "\\(c(a,b)d\\) then \\(a,b\\)" to "(c(a,b)d)",
            "\\(a)(b\\) then \\(b)(c\\)" to "(a)(b)(c)",
            "\\(b)(c\\) then \\(a)(b\\)" to "(a)(b)(c)",
            "\\(a)(b\\) then \\(b)(c\\)" to "(a)(b)(c) and (a)(b)",
        )
        cases.forEach { (body, html) ->
            val document = Jsoup.parse("<p>$html</p>")
            val before = document.outerHtml()
            assertThat(detector.detect(body, document)).isNull()
            assertThat(document.outerHtml()).isEqualTo(before)
            val result = detector.detect("$body then \$z\$", Jsoup.parse("<p>$html then \$z\$</p>"))!!
            assertThat(result.select("[$MATHS_ATTRIBUTE]").map { it.mathsTex() }).containsExactly("z")
        }
    }

    @Test
    fun `nonoverlapping formulas with similar text still map in order`() {
        val body = "\\(a,b\\) then \\(c(a,b)d\\)"
        // The inner candidate within the second occurrence makes the whole pair uncertain under L5.
        assertThat(detector.detect(body, Jsoup.parse("<p>(a,b) then (c(a,b)d)</p>"))).isNull()
        val separate = detector.detect("\\(a,b\\) then \\(c(a,c)d\\)", Jsoup.parse("<p>(a,b) then (c(a,c)d)</p>"))!!
        assertThat(separate.select("[$MATHS_ATTRIBUTE]").map { it.mathsTex() }).containsExactly("a,b", "c(a,c)d").inOrder()
    }

    @Test
    fun `both restructured formulas stay literal when their raw needles contain each other`() {
        val small = "\\(a\\,  \nb\\)"
        val containing = "\\(c  \n(a, b)d\\)"
        listOf(
            "$small then $containing" to "(a,<br>\nb) then (c<br>\n(a, b)d)",
            "$containing then $small" to "(c<br>\n(a, b)d) then (a,<br>\nb)",
        ).forEach { (body, html) ->
            val document = Jsoup.parse("<p>$html</p>")
            val original = document.outerHtml()
            assertThat(detector.detect(body, document)).isNull()
            assertThat(document.outerHtml()).isEqualTo(original)
            val withTrailing = Jsoup.parse("<p>$html then \$z\$</p>")
            val before = withTrailing.outerHtml()
            val result = detector.detect("$body then \$z\$", withTrailing)!!
            assertThat(result.select("[$MATHS_ATTRIBUTE]").map { it.mathsTex() }).containsExactly("z")
            result.select("[$MATHS_ATTRIBUTE]").forEach { it.replaceMathsWithFallback() }
            assertThat(result.outerHtml()).isEqualTo(before)
            assertThat(withTrailing.outerHtml()).isEqualTo(before)
        }
    }

    @Test
    fun `entity decoded containing formulas cannot donate inner text to restructured maths`() {
        val small = "\\(a\\,  \nb\\)"
        listOf("&#40;a, b&#41;", "&#x28;a, b&#x29;", "&lpar;a, b&rpar;", "\\&#40;a, b\\&#41;").forEach { inner ->
            val containing = "\\(c  \n${inner}d\\)"
            listOf(
                "$small then $containing" to "(a,<br>\nb) then (c<br>\n(a, b)d)",
                "$containing then $small" to "(c<br>\n(a, b)d) then (a,<br>\nb)",
            ).forEach { (body, html) ->
                val document = Jsoup.parse("<p>$html</p>")
                val before = document.outerHtml()
                assertThat(detector.detect(body, document)).isNull()
                assertThat(document.outerHtml()).isEqualTo(before)
                val trailing = Jsoup.parse("<p>$html then \$z\$</p>")
                val original = trailing.outerHtml()
                val result = detector.detect("$body then \$z\$", trailing)!!
                assertThat(result.select("[$MATHS_ATTRIBUTE]").map { it.mathsTex() }).containsExactly("z")
                result.select("[$MATHS_ATTRIBUTE]").forEach { it.replaceMathsWithFallback() }
                assertThat(result.outerHtml()).isEqualTo(original)
                assertThat(trailing.outerHtml()).isEqualTo(original)
            }
        }
    }

    @Test
    fun `entity decoded candidate overlaps retain their original key associations`() {
        val body = "\\(a)(b\\) then \\(b&#41;&#40;c\\)"
        val document = Jsoup.parse("<p>(a)(b)(c)</p>")
        assertThat(detector.detect(body, document)).isNull()
        val result = detector.detect("$body then \$z\$", Jsoup.parse("<p>(a)(b)(c) then \$z\$</p>"))!!
        assertThat(result.select("[$MATHS_ATTRIBUTE]").map { it.mathsTex() }).containsExactly("z")
    }

    @Test
    fun `entity ambiguity representations do not change insertion needles or raw TeX`() {
        val body = "\\(a&amp;b\\)"
        assertThat(detector.detect(body, Jsoup.parse("<p>(a&amp;b)</p>"))).isNull()
        val document = Jsoup.parse("<p>(a&amp;amp;b)</p>")
        val result = detector.detect(body, document)!!
        assertThat(result.selectFirst("[$MATHS_ATTRIBUTE]")!!.mathsTex()).isEqualTo("a&amp;b")
        result.select("[$MATHS_ATTRIBUTE]").forEach { it.replaceMathsWithFallback() }
        assertThat(result.outerHtml()).isEqualTo(document.outerHtml())
    }

    @Test
    fun `decoded only occurrences cannot satisfy repeated original insertion keys`() {
        listOf("&amp;", "&#38;", "&#x26;").forEach { entity ->
            val decoded = "\\(a\\,${entity}b\\)"
            val escaped = "\\(a,\\${entity}b\\)"
            val literal = entity.replace("&", "&amp;")
            listOf(
                "$decoded then $escaped" to "(a,&amp;b) then (a,${literal}b)",
                "$escaped then $decoded" to "(a,${literal}b) then (a,&amp;b)",
            ).forEach { (body, html) ->
                val document = Jsoup.parse("<p>$html</p>")
                val before = document.outerHtml()
                assertThat(detector.detect(body, document)).isNull()
                assertThat(document.outerHtml()).isEqualTo(before)
                val trailing = Jsoup.parse("<p>$html then \$z\$</p>")
                val original = trailing.outerHtml()
                val result = detector.detect("$body then \$z\$", trailing)!!
                assertThat(result.select("[$MATHS_ATTRIBUTE]").map { it.mathsTex() }).containsExactly("z")
                result.select("[$MATHS_ATTRIBUTE]").forEach { it.replaceMathsWithFallback() }
                assertThat(result.outerHtml()).isEqualTo(original)
                assertThat(trailing.outerHtml()).isEqualTo(original)
            }
        }
    }

    @Test
    fun `repeated entity insertion keys still render when every original occurrence exists`() {
        val body = "\\(a\\,&amp;b\\) then \\(a,\\&amp;b\\)"
        val document = Jsoup.parse("<p>(a,&amp;amp;b) then (a,&amp;amp;b)</p>")
        val result = detector.detect(body, document)!!
        assertThat(result.select("[$MATHS_ATTRIBUTE]").map { it.mathsTex() }).containsExactly("a\\,&amp;b", "a,\\&amp;b").inOrder()
        result.select("[$MATHS_ATTRIBUTE]").forEach { it.replaceMathsWithFallback() }
        assertThat(result.outerHtml()).isEqualTo(document.outerHtml())
    }

    @Test
    fun `escaped entity spellings in literal gaps cannot become maths`() {
        listOf("&amp;", "&#38;", "&#x26;").forEach { entity ->
            listOf(
                "a,\\${entity}b" to "a,${entity}b",
                "a,\\${entity}b&amp;c" to "a,${entity}b&c",
                "a,&amp;b\\${entity}c" to "a,&b${entity}c",
            ).forEach { (source, visible) ->
                val formula = "\\($visible\\)"
                val escapedFormula = "\\($source\\)"
                val display = "($visible)".replace("&", "&amp;")
                listOf(
                    "literal ($source) then $formula",
                    "$formula then literal ($source)",
                    "literal ($source) then $escapedFormula",
                ).forEach { body ->
                    val document = Jsoup.parse("<p>literal $display then $display</p>")
                    val before = document.outerHtml()
                    assertThat(detector.detect(body, document)).isNull()
                    assertThat(document.outerHtml()).isEqualTo(before)
                    val trailing = Jsoup.parse("<p>literal $display then $display and \$z\$</p>")
                    val original = trailing.outerHtml()
                    val result = detector.detect("$body and \$z\$", trailing)!!
                    assertThat(result.select("[$MATHS_ATTRIBUTE]").map { it.mathsTex() }).containsExactly("z")
                    result.select("[$MATHS_ATTRIBUTE]").forEach { it.replaceMathsWithFallback() }
                    assertThat(result.outerHtml()).isEqualTo(original)
                    assertThat(trailing.outerHtml()).isEqualTo(original)
                }
            }
        }
    }

    @Test
    fun `unknown entity prefixes cannot hide literal gap lookalikes`() {
        listOf("&notit;", "&ampersand;", "&copycat;").forEach { unknown ->
            listOf(
                "a,${unknown}b" to "a,${unknown}b",
                "a,${unknown}b&amp;c" to "a,${unknown}b&c",
                "a,${unknown}b&#38;c&#x26;d\\&amp;e" to "a,${unknown}b&c&d&amp;e",
                "a,&amp;b${unknown}c\\&#38;d" to "a,&b${unknown}c&#38;d",
            ).forEach { (source, visible) ->
                val display = "($visible)".replace("&", "&amp;")
                val formula = "\\(${visible.replace(unknown, "\\$unknown")}\\)"
                listOf(
                    "literal ($source) then $formula" to "literal $display then $display",
                    "$formula then literal ($source)" to "$display then literal $display",
                ).forEach { (body, html) ->
                    val document = Jsoup.parse("<p>$html</p>")
                    val before = document.outerHtml()
                    assertThat(detector.detect(body, document)).isNull()
                    assertThat(document.outerHtml()).isEqualTo(before)
                    val trailing = Jsoup.parse("<p>$html and \$z\$</p>")
                    val original = trailing.outerHtml()
                    val result = detector.detect("$body and \$z\$", trailing)!!
                    assertThat(result.select("[$MATHS_ATTRIBUTE]").map { it.mathsTex() }).containsExactly("z")
                    result.select("[$MATHS_ATTRIBUTE]").forEach { it.replaceMathsWithFallback() }
                    assertThat(result.outerHtml()).isEqualTo(original)
                    assertThat(trailing.outerHtml()).isEqualTo(original)
                }
            }
        }
    }

    @Test
    fun `later elements stay literal after cap`() {
        val body = (1..51).joinToString(" ") { "\$x$it\$" }
        val result = detector.detect(body, null)!!
        assertThat(result.select("[$MATHS_ATTRIBUTE]")).hasSize(50)
        assertThat((result.body().childNodes().last() as TextNode).wholeText).isEqualTo(" \$x51\$")
    }
}
