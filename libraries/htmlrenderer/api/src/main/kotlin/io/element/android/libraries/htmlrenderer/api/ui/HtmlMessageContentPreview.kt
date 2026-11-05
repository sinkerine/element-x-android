/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.htmlrenderer.api.ui

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withAnnotation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.htmlrenderer.api.BlockNode
import io.element.android.libraries.htmlrenderer.api.CodeBlockNode
import io.element.android.libraries.htmlrenderer.api.DocumentNode
import io.element.android.libraries.htmlrenderer.api.HtmlMessageParser.Companion.INLINE_CODE_ANNOTATION_TAG
import io.element.android.libraries.htmlrenderer.api.HtmlMessageParser.Companion.LINK_ANNOTATION_TAG
import io.element.android.libraries.htmlrenderer.api.ListItemNode
import io.element.android.libraries.htmlrenderer.api.ListNode
import io.element.android.libraries.htmlrenderer.api.MathsBlockNode
import io.element.android.libraries.htmlrenderer.api.MathsInlineContent
import io.element.android.libraries.htmlrenderer.api.MentionNodeContent
import io.element.android.libraries.htmlrenderer.api.ParagraphNode
import io.element.android.libraries.htmlrenderer.api.QuoteNode
import io.element.android.libraries.htmlrenderer.api.spans.InlineCodeSpanStyle
import io.element.android.libraries.maths.api.MathsLayout
import io.element.android.libraries.matrix.api.core.UserId
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList

@PreviewsDayNight
@Composable
internal fun HtmlMessageContentPreview(
    @PreviewParameter(HtmlMessageContentPreviewParam::class) node: DocumentNode,
) = ElementPreview {
    HtmlMessageContent(
        node = node,
        currentUserId = UserId("@me:example.org"),
        modifier = Modifier.padding(16.dp),
    )
}

internal class HtmlMessageContentPreviewParam : PreviewParameterProvider<DocumentNode> {
    override val values: Sequence<DocumentNode>
        get() = sequenceOf(
            richParagraph(),
            inlineCode(),
            multilineInlineCode(),
            orderedList(),
            quote(),
            nestedQuotes(),
            codeBlock(),
            mention(),
            mentionOwn(),
            nestedList(),
            nestedListWithComplexContents(),
            linkifiedParagraph(),
            inlineMaths(),
            quotedInlineMaths(),
            inlineMaths(widthEm = 50f),
            displayMaths(widthEm = 5f),
            displayMaths(widthEm = 50f),
            mathsFallback(),
        )

    private fun richParagraph() = document(
        paragraph(
            buildAnnotatedString {
                append("Hello ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("bold") }
                append(", ")
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("italic") }
                append(" and a ")
                val start = length
                append("link")
                addStringAnnotation(LINK_ANNOTATION_TAG, "https://element.io", start, length)
            }
        ),
    )

    private fun linkifiedParagraph() = document(
        paragraph(
            buildAnnotatedString {
                append("Hello me@matrix.org, check out element.io and and call +1 234 567 8900, then get in touch with ")
                appendInlineContent(MENTION_ID, "@alice:example.org")
                append(".\n\n")
                append("Also, ignore this link: ")
                val start = length
                append("https://matrix.org")
                addStringAnnotation(LINK_ANNOTATION_TAG, "https://matrix.org", start, length)
                append(" and this one: ")
                withAnnotation(INLINE_CODE_ANNOTATION_TAG, "") {
                    append("https://element.io")
                }
                append(".")
            },
            inlineContent = persistentMapOf(
                MENTION_ID to MentionNodeContent.User(displayText = "@alice", userId = UserId("@alice:example.org")),
            ),
        ),
    )

    private fun inlineCode() = document(
        paragraph(
            buildAnnotatedString {
                append("Run ")
                val start = length
                withStyle(InlineCodeSpanStyle) { append("git status") }
                addStringAnnotation(INLINE_CODE_ANNOTATION_TAG, "", start, length)
                append(" to see changes")
            }
        ),
    )

    private fun multilineInlineCode() = document(
        QuoteNode(persistentListOf(
            paragraph(
                buildAnnotatedString {
                    append("Trying ")
                    val start = length
                    withStyle(InlineCodeSpanStyle) {
                        append("inline code when it needs to be wrapped in several lines, just to check what it looks like")
                    }
                    addStringAnnotation(INLINE_CODE_ANNOTATION_TAG, "", start, length)
                    append(", is it good?")
                }
            ),
        ))
    )

    private fun orderedList() = document(
        ListNode(
            ordered = true,
            startIndex = 1,
            items = persistentListOf(
                ListItemNode(persistentListOf(paragraph(AnnotatedString("First item")))),
                ListItemNode(persistentListOf(paragraph(AnnotatedString("Second item")))),
            ),
        ),
    )

    private fun quote() = document(
        paragraph(AnnotatedString("Someone said:")),
        QuoteNode(persistentListOf(paragraph(AnnotatedString("This is a quoted message.")))),
    )

    private fun nestedQuotes() = document(
        paragraph(AnnotatedString("Someone said:")),
        QuoteNode(persistentListOf(
            paragraph(AnnotatedString("This is a quoted message.")),
            QuoteNode(persistentListOf(
                paragraph(AnnotatedString("This is a nested quote.")),
                codeBlock(),
            )),
        )),
    )

    private fun codeBlock() = document(
        CodeBlockNode("fun main() {\n    println(\"Hello, world!\")\n}"),
    )

    private fun mention() = document(
        paragraph(
            text = buildAnnotatedString {
                append("Hey ")
                appendInlineContent(MENTION_ID, "@alice")
                append(", welcome!")
            },
            inlineContent = persistentMapOf(
                MENTION_ID to MentionNodeContent.User(displayText = "@alice", userId = UserId("@alice:example.org")),
            ),
        ),
    )

    private fun mentionOwn() = document(
        paragraph(
            text = buildAnnotatedString {
                append("Hey ")
                appendInlineContent(MENTION_ID, "@me")
                append(", welcome!")
            },
            inlineContent = persistentMapOf(
                MENTION_ID to MentionNodeContent.User(displayText = "@me", userId = UserId("@me:example.org")),
            ),
        ),
    )

    private fun nestedList() = document(
        ListNode(
            ordered = false,
            startIndex = 1,
            items = persistentListOf(
                ListItemNode(
                    persistentListOf(
                        paragraph(AnnotatedString("Parent item")),
                        ListNode(
                            ordered = false,
                            startIndex = 1,
                            items = persistentListOf(
                                ListItemNode(persistentListOf(paragraph(AnnotatedString("Nested item")))),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun nestedListWithComplexContents() = document(
        ListNode(
            ordered = false,
            startIndex = 1,
            items = persistentListOf(
                ListItemNode(
                    persistentListOf(
                        paragraph(AnnotatedString("Parent item")),
                        ListNode(
                            ordered = false,
                            startIndex = 1,
                            items = persistentListOf(
                                ListItemNode(persistentListOf(
                                    paragraph(AnnotatedString("Nested item")),
                                    codeBlock(),
                                    quote(),
                                )),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun inlineMaths(widthEm: Float = 5f) = document(
        ParagraphNode(
            text = buildAnnotatedString {
                append("Euler ")
                appendInlineContent("maths_0", "e^{i\\pi}+1=0")
                append(" holds for ")
                appendInlineContent(MENTION_ID, "Alice")
            },
            inlineContent = persistentMapOf(
                MENTION_ID to MentionNodeContent.User(displayText = "Alice", userId = UserId("@alice:example.org")),
            ),
            inlineMaths = persistentMapOf("maths_0" to MathsInlineContent("e^{i\\pi}+1=0", PreviewMathsLayout(widthEm))),
        ),
    )

    private fun quotedInlineMaths() = document(
        QuoteNode(persistentListOf(
            inlineMaths(),
            ListNode(
                ordered = false,
                startIndex = 1,
                items = persistentListOf(ListItemNode(persistentListOf(QuoteNode(inlineMaths().children)))),
            ),
        )),
    )

    private fun displayMaths(widthEm: Float) = document(
        paragraph(AnnotatedString("A display formula:")),
        MathsBlockNode("x", PreviewMathsLayout(widthEm, heightEm = 1.5f, depthEm = 0.5f)),
    )

    private fun mathsFallback() = document(
        paragraph(buildAnnotatedString {
            append("Invalid maths: ")
            withAnnotation(INLINE_CODE_ANNOTATION_TAG, "") {
                withStyle(InlineCodeSpanStyle) { append("\\frac{") }
            }
        }),
    )

    private fun document(vararg blocks: BlockNode) = DocumentNode(blocks.toList().toImmutableList())

    private fun paragraph(
        text: AnnotatedString,
        inlineContent: ImmutableMap<String, MentionNodeContent> = persistentMapOf(),
    ) = ParagraphNode(text = text, inlineContent = inlineContent)

    private companion object {
        const val MENTION_ID = "mention_0"
    }
}

/** Native-free stand-in for previewing formula bounds, baseline and horizontal scrolling. */
private class PreviewMathsLayout(
    override val widthEm: Float,
    override val heightEm: Float = 0.9f,
    override val depthEm: Float = 0.3f,
) : MathsLayout {
    override fun draw(canvas: Canvas, fontSizePx: Float, color: Int) {
        val paint = Paint().apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        val width = widthEm * fontSizePx
        val height = (heightEm + depthEm) * fontSizePx
        canvas.drawRect(0f, 0f, width, height, paint)
        canvas.drawLine(0f, heightEm * fontSizePx, width, heightEm * fontSizePx, paint)
        canvas.drawLine(0f, height, width, 0f, paint)
    }
}
