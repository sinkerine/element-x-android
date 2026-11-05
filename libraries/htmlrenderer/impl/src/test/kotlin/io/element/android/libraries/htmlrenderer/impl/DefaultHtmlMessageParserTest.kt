/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.htmlrenderer.impl

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.htmlrenderer.api.CodeBlockNode
import io.element.android.libraries.htmlrenderer.api.HtmlMessageParser
import io.element.android.libraries.htmlrenderer.api.ListNode
import io.element.android.libraries.htmlrenderer.api.MathsBlockNode
import io.element.android.libraries.htmlrenderer.api.MathsInlineContent
import io.element.android.libraries.htmlrenderer.api.MentionNodeContent
import io.element.android.libraries.htmlrenderer.api.ParagraphNode
import io.element.android.libraries.htmlrenderer.api.QuoteNode
import io.element.android.libraries.maths.api.MAX_MATHS_ELEMENTS_PER_MESSAGE
import io.element.android.libraries.maths.api.MathsLayoutEngine
import io.element.android.libraries.maths.test.FakeMathsLayout
import io.element.android.libraries.maths.test.FakeMathsLayoutEngine
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomAlias
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.core.toRoomIdOrAlias
import io.element.android.libraries.matrix.api.permalink.PermalinkData
import io.element.android.libraries.matrix.test.permalink.FakePermalinkParser
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.jsoup.Jsoup
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DefaultHtmlMessageParserTest : RobolectricTest() {
    @Test
    fun `plain text becomes a single paragraph`() {
        val document = parse("Hello world")
        val paragraph = document.children.single() as ParagraphNode
        assertThat(paragraph.text.text).isEqualTo("Hello world")
        assertThat(paragraph.inlineContent).isEmpty()
    }

    @Test
    fun `inline formatting is encoded as span styles`() {
        val document = parse("Hello <strong>bold</strong> and <em>italic</em>")
        val paragraph = document.children.single() as ParagraphNode
        assertThat(paragraph.text.text).isEqualTo("Hello bold and italic")

        val boldStart = paragraph.text.text.indexOf("bold")
        val bold = paragraph.text.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        assertThat(bold.start).isEqualTo(boldStart)
        assertThat(bold.end).isEqualTo(boldStart + "bold".length)

        val italicStart = paragraph.text.text.indexOf("italic")
        val italic = paragraph.text.spanStyles.single { it.item.fontStyle == FontStyle.Italic }
        assertThat(italic.start).isEqualTo(italicStart)
        assertThat(italic.end).isEqualTo(italicStart + "italic".length)
    }

    @Test
    fun `unordered list produces a bulleted ListNode`() {
        val document = parse("<ul><li>First</li><li>Second</li></ul>")
        val list = document.children.single() as ListNode
        assertThat(list.ordered).isFalse()
        assertThat(list.startIndex).isEqualTo(1)
        assertThat(list.items).hasSize(2)
        val firstItem = list.items[0].children.single() as ParagraphNode
        assertThat(firstItem.text.text).isEqualTo("First")
    }

    @Test
    fun `ordered list honors the start attribute`() {
        val document = parse("""<ol start="5"><li>Fifth</li></ol>""")
        val list = document.children.single() as ListNode
        assertThat(list.ordered).isTrue()
        assertThat(list.startIndex).isEqualTo(5)
    }

    @Test
    fun `a list nested inside a list item is preserved`() {
        val document = parse("<ul><li>Parent<ul><li>Child</li></ul></li></ul>")
        val outerList = document.children.single() as ListNode
        val outerItem = outerList.items.single()
        // The item holds a paragraph ("Parent") followed by the nested list.
        val paragraph = outerItem.children[0] as ParagraphNode
        assertThat(paragraph.text.text).isEqualTo("Parent")
        val nestedList = outerItem.children[1] as ListNode
        val nestedItem = nestedList.items.single().children.single() as ParagraphNode
        assertThat(nestedItem.text.text).isEqualTo("Child")
    }

    @Test
    fun `blockquote wraps its block children`() {
        val document = parse("<blockquote><p>Quoted</p></blockquote>")
        val quote = document.children.single() as QuoteNode
        val paragraph = quote.children.single() as ParagraphNode
        assertThat(paragraph.text.text).isEqualTo("Quoted")
    }

    @Test
    fun `blockquotes can nest`() {
        val document = parse("<blockquote><blockquote><p>Deep</p></blockquote></blockquote>")
        val outer = document.children.single() as QuoteNode
        val inner = outer.children.single() as QuoteNode
        val paragraph = inner.children.single() as ParagraphNode
        assertThat(paragraph.text.text).isEqualTo("Deep")
    }

    @Test
    fun `inline code is monospaced and annotated`() {
        val document = parse("Run <code>git status</code> now")
        val paragraph = document.children.single() as ParagraphNode
        assertThat(paragraph.text.text).isEqualTo("Run git status now")

        val start = paragraph.text.text.indexOf("git status")
        val end = start + "git status".length
        val monospace = paragraph.text.spanStyles.single { it.item.fontFamily == FontFamily.Monospace }
        assertThat(monospace.start).isEqualTo(start)
        assertThat(monospace.end).isEqualTo(end)

        val codeAnnotations = paragraph.text.getStringAnnotations(HtmlMessageParser.INLINE_CODE_ANNOTATION_TAG, start, end)
        assertThat(codeAnnotations).hasSize(1)
    }

    @Test
    fun `pre becomes a code block with raw text, and its inner code is not treated as inline code`() {
        val document = parse("<pre><code>val x = 1\nval y = 2</code></pre>")
        val codeBlock = document.children.single() as CodeBlockNode
        assertThat(codeBlock.code).isEqualTo("val x = 1\nval y = 2")
    }

    @Test
    fun `a regular link is styled and annotated with its url`() {
        val permalinkParser = FakePermalinkParser { PermalinkData.FallbackLink(android.net.Uri.parse(it)) }
        val document = parse("""Visit <a href="https://element.io">the site</a>""", permalinkParser)
        val paragraph = document.children.single() as ParagraphNode
        assertThat(paragraph.inlineContent).isEmpty()

        val linkStart = paragraph.text.text.indexOf("the site")
        val annotations = paragraph.text.getStringAnnotations(
            tag = HtmlMessageParser.LINK_ANNOTATION_TAG,
            start = linkStart,
            end = linkStart + "the site".length,
        )
        assertThat(annotations.single().item).isEqualTo("https://element.io")
    }

    @Test
    fun `a user link becomes a mention pill`() {
        val userId = UserId("@alice:example.org")
        val permalinkParser = FakePermalinkParser { PermalinkData.UserLink(userId) }
        val document = parse("""Hi <a href="https://matrix.to/#/@alice:example.org">Alice</a>""", permalinkParser)
        val paragraph = document.children.single() as ParagraphNode

        val mention = paragraph.inlineContent.values.single()
        assertThat(mention).isEqualTo(MentionNodeContent.User(displayText = "Alice", userId = userId))
        // The placeholder id in the text matches the map key.
        val placeholderId = paragraph.inlineContent.keys.single()
        val annotations = paragraph.text.getStringAnnotations(0, paragraph.text.length)
        assertThat(annotations.any { it.item == placeholderId }).isTrue()
    }

    @Test
    fun `a room link becomes a room mention pill`() {
        val roomIdOrAlias = RoomAlias("#room:example.org").toRoomIdOrAlias()
        val permalinkParser = FakePermalinkParser {
            PermalinkData.RoomLink(roomIdOrAlias = roomIdOrAlias)
        }
        val document = parse("""See <a href="https://matrix.to/#/#room:example.org">the room</a>""", permalinkParser)
        val paragraph = document.children.single() as ParagraphNode
        val mention = paragraph.inlineContent.values.single()
        assertThat(mention).isEqualTo(
            MentionNodeContent.Room(displayText = "the room", roomIdOrAlias = roomIdOrAlias)
        )
    }

    @Test
    fun `a link to an event is a regular link, not a mention`() {
        val permalinkParser = FakePermalinkParser {
            PermalinkData.RoomLink(
                roomIdOrAlias = RoomId("!room:example.org").toRoomIdOrAlias(),
                eventId = EventId("\$Rqnc-F-dvnEYJTyHq_iKxU2bZ1CI92-kuZq3a5lr5Zg"),
            )
        }
        val document = parse("""Look <a href="https://matrix.to/#/!room:example.org/event">here</a>""", permalinkParser)
        val paragraph = document.children.single() as ParagraphNode
        assertThat(paragraph.inlineContent).isEmpty()
    }

    @Test
    fun `mx-reply content is stripped`() {
        val document = parse("<mx-reply>In reply to something</mx-reply>Actual message")
        val paragraph = document.children.single() as ParagraphNode
        assertThat(paragraph.text.text).isEqualTo("Actual message")
    }

    @Test
    fun `blank input produces an empty document`() {
        val document = parse("   ")
        assertThat(document.children).isEmpty()
    }

    @Test
    fun `inline maths uses a placeholder with TeX alternate text and immutable content`() {
        val layout = FakeMathsLayout()
        val engine = FakeMathsLayoutEngine { _, _ -> layout }
        val document = parse("Euler <span data-mx-maths='x'><code>x</code></span> holds", mathsLayoutEngine = engine)
        val paragraph = document.children.single() as ParagraphNode
        val expectedText = buildAnnotatedString {
            append("Euler ")
            appendInlineContent("maths_0", "x")
            append(" holds")
        }
        assertThat(paragraph.text).isEqualTo(expectedText)
        assertThat(paragraph.inlineMaths).containsExactly("maths_0", MathsInlineContent("x", layout))
        assertThat(engine.calls).containsExactly("x" to false)
    }

    @Test
    fun `display maths becomes a block at the top level in a list and in a quote`() {
        val engine = FakeMathsLayoutEngine()
        val display = "<div data-mx-maths='x'><code>x</code></div>"
        val document = parse("$display<ul><li>$display</li></ul><blockquote>$display</blockquote>", mathsLayoutEngine = engine)
        assertThat(document.children[0]).isInstanceOf(MathsBlockNode::class.java)
        assertThat((document.children[1] as ListNode).items.single().children.single()).isInstanceOf(MathsBlockNode::class.java)
        assertThat((document.children[2] as QuoteNode).children.single()).isInstanceOf(MathsBlockNode::class.java)
        assertThat(engine.calls).containsExactly("x" to true, "x" to true, "x" to true)
    }

    @Test
    fun `display maths in an inline wrapper renders inline with formatting`() {
        val engine = FakeMathsLayoutEngine()
        val paragraph = parse("<b><div data-mx-maths='x'>x</div></b>", mathsLayoutEngine = engine).children.single() as ParagraphNode
        assertThat(paragraph.inlineMaths).hasSize(1)
        assertThat(paragraph.text.spanStyles.single().item.fontWeight).isEqualTo(FontWeight.Bold)
        assertThat(engine.calls).containsExactly("x" to false)
    }

    @Test
    fun `null layouts preserve inline code and display paragraph fallbacks`() {
        val engine = FakeMathsLayoutEngine { _, _ -> null }
        val document = parse(
            "Before <span data-mx-maths='bad'><code>sender fallback</code></span> after" +
                "<div data-mx-maths='bad'><code>display fallback</code></div>",
            mathsLayoutEngine = engine,
        )
        val inline = document.children[0] as ParagraphNode
        assertThat(inline.text.text).isEqualTo("Before sender fallback after")
        assertThat(inline.inlineMaths).isEmpty()
        assertThat(inline.text.getStringAnnotations(HtmlMessageParser.INLINE_CODE_ANNOTATION_TAG, 0, inline.text.length)).hasSize(1)
        val display = document.children[1] as ParagraphNode
        assertThat(display.text.text).isEqualTo("display fallback")
        assertThat(display.inlineMaths).isEmpty()
    }

    @Test
    fun `null layouts preserve detected original literal text including delimiters`() {
        val engine = FakeMathsLayoutEngine { _, _ -> null }
        val document = parse(
            "<span data-mx-maths='x'>${'$'}x${'$'}</span><div data-mx-maths='y'>\\[y\\]</div>",
            mathsLayoutEngine = engine,
        )
        assertThat((document.children[0] as ParagraphNode).text.text).isEqualTo("${'$'}x${'$'}")
        assertThat((document.children[1] as ParagraphNode).text.text).isEqualTo("\\[y\\]")
        assertThat((document.children[0] as ParagraphNode).text.spanStyles).isEmpty()
    }

    @Test
    fun `maths inside code pre or another maths element is never laid out`() {
        val engine = FakeMathsLayoutEngine { _, _ -> null }
        val span = "<span data-mx-maths='x'>x</span>"
        val document = parse("<code>$span</code><pre>$span</pre><span data-mx-maths='outer'>$span</span>", mathsLayoutEngine = engine)
        assertThat(engine.calls).containsExactly("outer" to false)
        assertThat((document.children[0] as ParagraphNode).inlineMaths).isEmpty()
        assertThat((document.children[1] as CodeBlockNode).code).isEqualTo("x")
    }

    @Test
    fun `the maths cap spans paragraphs display blocks and failed layouts and resets per parse`() {
        val engine = FakeMathsLayoutEngine { tex, _ -> if (tex == "bad") null else FakeMathsLayout() }
        val parser = DefaultHtmlMessageParser(FakePermalinkParser(), engine)
        val html = "<span data-mx-maths='bad'><code>bad</code></span>" +
            "<div data-mx-maths='x'>x</div>".repeat(MAX_MATHS_ELEMENTS_PER_MESSAGE - 1) +
            "<p><span data-mx-maths='later'><code>later</code></span></p>"
        repeat(2) {
            val document = parser.parse(Jsoup.parse(html))
            assertThat(document.children.filterIsInstance<MathsBlockNode>()).hasSize(MAX_MATHS_ELEMENTS_PER_MESSAGE - 1)
            val last = document.children.last() as ParagraphNode
            assertThat(last.text.text).isEqualTo("later")
            assertThat(last.inlineMaths).isEmpty()
        }
        assertThat(engine.calls).hasSize(2 * MAX_MATHS_ELEMENTS_PER_MESSAGE)
        assertThat(engine.calls.none { it.first == "later" }).isTrue()
    }

    @Test
    fun `concurrent parses each get their own maths cap`() {
        val barrier = CyclicBarrier(2)
        val layout = FakeMathsLayout()
        val engine = object : MathsLayoutEngine {
            override fun layout(tex: String, displayMode: Boolean) = layout.also {
                barrier.await(10, TimeUnit.SECONDS)
            }
        }
        val parser = DefaultHtmlMessageParser(FakePermalinkParser(), engine)
        val html = "<span data-mx-maths='x'>x</span>".repeat(MAX_MATHS_ELEMENTS_PER_MESSAGE + 1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { executor.submit<ParagraphNode> { parser.parse(Jsoup.parse(html)).children.single() as ParagraphNode } }
            results.forEach { assertThat(it.get(20, TimeUnit.SECONDS).inlineMaths).hasSize(MAX_MATHS_ELEMENTS_PER_MESSAGE) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `maths only paragraphs are retained and mentions coexist with maths`() {
        val maths = "<span data-mx-maths='x'>x</span>"
        val onlyMaths = parse(maths).children.single() as ParagraphNode
        assertThat(onlyMaths.inlineMaths).hasSize(1)
        val userId = UserId("@alice:example.org")
        val paragraph = parse(
            "$maths<a href='https://matrix.to/#/@alice:example.org'>Alice</a>",
            FakePermalinkParser { PermalinkData.UserLink(userId) },
        ).children.single() as ParagraphNode
        assertThat(paragraph.inlineContent.keys).containsExactly("mention_0")
        assertThat(paragraph.inlineMaths.keys).containsExactly("maths_0")
        assertThat(paragraph.text.text).isEqualTo("xAlice")
    }

    @Test
    fun `ordinary messages never invoke the maths engine and input is never mutated`() {
        val engine = FakeMathsLayoutEngine()
        val parser = DefaultHtmlMessageParser(FakePermalinkParser(), engine)
        parser.parse(Jsoup.parse("<p>Ordinary <b>text</b></p>"))
        assertThat(engine.calls).isEmpty()
        val document = Jsoup.parse("<span data-mx-maths='x'><code>x</code></span>")
        val original = document.outerHtml()
        parser.parse(document)
        assertThat(document.outerHtml()).isEqualTo(original)
    }

    private fun parse(
        html: String,
        permalinkParser: FakePermalinkParser = FakePermalinkParser(),
        mathsLayoutEngine: MathsLayoutEngine = FakeMathsLayoutEngine(),
    ) = DefaultHtmlMessageParser(permalinkParser, mathsLayoutEngine).parse(Jsoup.parse(html))
}
