/*
 * Copyright 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.factories.event

import io.element.android.features.messages.impl.timeline.model.event.AnswerNavigation
import io.element.android.features.messages.impl.timeline.model.event.AnswerSection
import io.element.android.features.messages.impl.timeline.model.event.AnswerSource
import io.element.android.libraries.core.data.tryOrNull
import kotlinx.collections.immutable.toImmutableList
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

/**
 * Reads structure only. Every fragment must go through the caller's existing sanitizer and span converter before display.
 * Top-level headings are the section boundaries, as emitted by Markdown. Nested/quoted content is never promoted into a section.
 */
internal object AnswerNavigationParser {
    private const val EXCLUDED = "blockquote, mx-reply, pre, code, [data-mx-spoiler], script, style"
    private val headingTags = (1..6).map { "h$it" }.toSet()

    fun parse(html: String, renderedLength: Int, render: (String) -> CharSequence): AnswerNavigation? {
        if (renderedLength < 2_000) return null
        val body = Jsoup.parseBodyFragment(html).body()
        val headings = body.children().filter { it.normalName() in headingTags && it.text().isNotBlank() && it.select(EXCLUDED).isEmpty() }
        if (headings.size < 3) return null
        val readable = body.clone().apply { select(EXCLUDED).remove() }
        if (render(readable.html()).length < 2_000) return null
        val fragments = mutableListOf(StringBuilder())
        body.childNodes().forEach { node ->
            if (node in headings) fragments.add(StringBuilder()) else fragments.last().append(node.outerHtml())
        }
        val sections = headings.mapIndexed { index, heading ->
            AnswerSection(
                title = heading.text(),
                level = heading.normalName().last().digitToInt(),
                // Render the heading contents with normal link, mention and maths handling, without trusting HTML IDs.
                heading = render("<p>${heading.html()}</p>"),
                body = render(fragments[index + 1].toString()),
            )
        }
        return AnswerNavigation(
            introduction = render(fragments.first().toString()),
            sections = sections.toImmutableList(),
            sources = sources(body, render).toImmutableList(),
        )
    }

    private fun sources(body: Element, render: (String) -> CharSequence): List<AnswerSource> {
        var sourceLevel: Int? = null
        val sources = linkedMapOf<String, AnswerSource>()
        body.children().forEach { element ->
            if (element.normalName() in headingTags) {
                val level = element.normalName().last().digitToInt()
                if (sourceLevel?.let { level <= it } == true) sourceLevel = null
                if (element.select(EXCLUDED).isEmpty() && element.text().lowercase() in setOf("sources", "references")) {
                    sourceLevel = minOf(sourceLevel ?: level, level)
                }
            }
            if (sourceLevel != null) {
                element.select("a[href]").filter { anchor ->
                    anchor.select(EXCLUDED).isEmpty() && anchor.parents().none { it.`is`(EXCLUDED) }
                }.forEach { anchor ->
                    // A sanitized fragment must still contain the author's visible label.
                    val label = render(anchor.outerHtml()).toString().trim()
                    val url = anchor.attr("href")
                    val uri = tryOrNull { URI(url) }
                    val host = uri?.host
                    if (uri?.scheme?.lowercase() in setOf("http", "https") && !host.isNullOrBlank() && label.isNotBlank()) {
                        sources.putIfAbsent(url, AnswerSource(label, url, host.lowercase()))
                    }
                }
            }
        }
        return sources.values.toList()
    }
}
