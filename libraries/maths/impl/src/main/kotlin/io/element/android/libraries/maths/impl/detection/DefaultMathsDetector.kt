/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.maths.impl.detection

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.maths.api.MATHS_ATTRIBUTE
import io.element.android.libraries.maths.api.MAX_MATHS_ELEMENTS_PER_MESSAGE
import io.element.android.libraries.maths.api.MathsDetector
import io.element.android.libraries.maths.api.createMathsElement
import io.element.android.libraries.maths.api.isMathsElement
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import timber.log.Timber

@ContributesBinding(AppScope::class)
class DefaultMathsDetector : MathsDetector {
    override fun detect(body: String, document: Document?): Document? {
        if ('$' !in body && "\\(" !in body && "\\[" !in body) return null
        return try {
            // A maths-aware sender's spec markup takes precedence over delimiter detection.
            if (document?.getElementsByAttribute(MATHS_ATTRIBUTE)?.isNotEmpty() == true) return null
            val matches = MathsDelimiters.find(body, Int.MAX_VALUE)
            if (matches.isEmpty()) {
                null
            } else if (document == null) {
                plainDocument(body, matches.take(MAX_MATHS_ELEMENTS_PER_MESSAGE))
            } else {
                insertIntoClone(body, document, matches)
            }
        } catch (error: Exception) {
            Timber.w("Maths detection failed (%s)", error.javaClass.simpleName)
            null
        } catch (error: StackOverflowError) {
            // Malformed DOM operations may exhaust the stack; retain the original message.
            Timber.w("Maths detection failed (%s)", error.javaClass.simpleName)
            null
        }
    }

    private fun plainDocument(body: String, matches: List<MathsMatch>): Document = Document.createShell("").apply {
        var cursor = 0
        matches.forEach { match ->
            var gapEnd = match.range.first
            if (match.displayMode && gapEnd > cursor && body[gapEnd - 1] == '\n') gapEnd--
            appendPlainText(body(), body.substring(cursor, gapEnd))
            body().appendChild(mathsElement(this, match, match.displayMode, body.substring(match.range)))
            cursor = match.range.last + 1
            if (match.displayMode && cursor < body.length && body[cursor] == '\n') cursor++
        }
        appendPlainText(body(), body.substring(cursor))
    }

    private fun appendPlainText(parent: Element, text: String) {
        text.split('\n').forEachIndexed { index, line ->
            if (index > 0) parent.appendElement("br")
            if (line.isNotEmpty()) parent.appendChild(TextNode(line))
        }
    }

    private fun insertIntoClone(body: String, document: Document, matches: List<MathsMatch>): Document? {
        val copy = document.clone()
        val nodes = mutableListOf<TextNode>()
        collectTextNodes(copy.body(), nodes)
        val budget = MathsSearchBudget()
        // Literal lookalikes outside maths make occurrence mapping ambiguous after Markdown restructuring (L5).
        val gaps = buildList {
            var end = 0
            matches.forEach {
                addAll(ambiguityTexts(body.substring(end, it.range.first), budget))
                end = it.range.last + 1
            }
            addAll(ambiguityTexts(body.substring(end), budget))
        }
        val needles = matches.map { unescapeMarkdown(body.substring(it.range)) }
        val keys = needles.map { mathsTextKey(it, budget) }
        // Resolve collisions before mutation: a restructured formula must not consume another formula's text (L5).
        val visibleKeys = matches.map { mathsTextKey(normalizeMarkdownText(body.substring(it.range), budget), budget) }
        val ambiguous = ambiguousKeys(nodes, keys, visibleKeys, budget)
        var nodeIndex = 0
        var inserted = false
        matches.take(MAX_MATHS_ELEMENTS_PER_MESSAGE).forEachIndexed { index, match ->
            val needle = needles[index]
            if (keys[index] in ambiguous) return@forEachIndexed
            if (gaps.any { findMathsText(it, needle, budget) != null }) return@forEachIndexed
            // A failed search must not consume the cursor: a later raw match may still map into this text run.
            var searchIndex = nodeIndex
            var hit: IntRange? = null
            while (searchIndex < nodes.size) {
                hit = findMathsText(nodes[searchIndex].wholeText, needle, budget)
                if (hit != null) break
                searchIndex++
            }
            if (hit == null) return@forEachIndexed
            val node = nodes[searchIndex]
            val text = node.wholeText
            val parent = node.parent() as Element
            val wholeRun = text.substring(0, hit.first).isBlank() && text.substring(hit.last + 1).isBlank()
            val wholeParagraph = parent.normalName() == "p" && parent.childNodes().all {
                it === node || it is TextNode && it.isBlank
            }
            val display = match.displayMode && wholeRun && (wholeParagraph || parent.normalName() in setOf("body", "blockquote", "li"))
            val maths = mathsElement(copy, match, display, text.substring(hit))
            if (display && wholeParagraph) {
                // Retain whitespace around the replaced text as well as the original visible fallback.
                maths.empty().appendChild(TextNode(text))
                parent.replaceWith(maths)
                nodeIndex = searchIndex + 1
            } else {
                if (hit.first > 0) node.before(TextNode(text.substring(0, hit.first)))
                node.before(maths)
                node.text(text.substring(hit.last + 1))
                nodeIndex = searchIndex
            }
            inserted = true
        }
        return copy.takeIf { inserted }
    }

    private fun ambiguousKeys(nodes: List<TextNode>, keys: List<String>, visibleKeys: List<String>, budget: MathsSearchBudget): Set<String> {
        val required = keys.groupingBy { it }.eachCount()
        // Alternate visible representations protect correspondence; insertion still uses the original needle and TeX.
        val faithful = keys.zip(visibleKeys).groupBy({ it.first }, { it.second })
        val representations = required.keys.associateWith { key ->
            (listOf(key, mathsTextKey(Parser.unescapeEntities(key, false), budget)) + faithful.getValue(key)).distinct()
        }
        val found = mutableMapOf<String, Int>()
        val ambiguous = mutableSetOf<String>()
        // A containing raw formula may have no complete DOM candidate after restructuring (L5).
        required.keys.forEach { containing ->
            required.keys.forEach { contained ->
                if (containing != contained && representations.getValue(containing).any { outer ->
                        representations.getValue(contained).any { inner -> findMathsText(outer, inner, budget) != null }
                    }
                ) {
                    ambiguous.add(containing)
                    ambiguous.add(contained)
                }
            }
        }
        nodes.forEach { node ->
            val hits = mutableListOf<Pair<String, IntRange>>()
            representations.forEach { (key, variants) ->
                val seen = mutableSetOf<IntRange>()
                variants.forEach { needle ->
                    var offset = 0
                    while (offset < node.wholeText.length) {
                        val hit = findMathsText(node.wholeText, needle, budget, offset) ?: break
                        offset = hit.first + 1
                        if (seen.add(hit)) {
                            hits.forEach { (other, range) ->
                                budget.spend()
                                if (hit.first <= range.last && range.first <= hit.last) {
                                    ambiguous.add(other)
                                    ambiguous.add(key)
                                }
                            }
                            hits.add(key to hit)
                            // Decoded-only candidates protect ambiguity but cannot satisfy an insertion occurrence.
                            if (needle == key) found[key] = (found[key] ?: 0) + 1
                        }
                    }
                }
            }
        }
        ambiguous.addAll(required.filter { (key, count) -> count > 1 && found.getOrDefault(key, 0) < count }.keys)
        return ambiguous
    }

    // Keep conservative variants alongside faithful source-order text; none controls insertion.
    private fun ambiguityTexts(text: String, budget: MathsSearchBudget): List<String> = listOf(
        normalizeMarkdownText(text, budget),
        Parser.unescapeEntities(unescapeMarkdown(text), false),
    ).distinct()

    private fun collectTextNodes(node: Node, result: MutableList<TextNode>) {
        val pending = ArrayDeque<Node>()
        pending.add(node)
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            if (current is TextNode) {
                result.add(current)
            } else if (current !is Element || current.normalName() !in setOf("pre", "code", "a", "mx-reply") && !current.isMathsElement()) {
                current.childNodes().asReversed().forEach { pending.add(it) }
            }
        }
    }

    private fun mathsElement(document: Document, match: MathsMatch, display: Boolean, fallback: String): Element =
        createMathsElement(document, match.tex, display).apply {
            // Detection must fall back to the original visible text, including delimiters, without adding code styling (R6).
            empty().appendChild(TextNode(fallback))
        }
}
