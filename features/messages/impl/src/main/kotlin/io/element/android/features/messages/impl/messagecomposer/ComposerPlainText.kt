/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.messagecomposer

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

internal fun htmlToComposerPlainText(html: String): String {
    val body = Jsoup.parseBodyFragment(html).body()
    return buildString {
        fun appendNode(node: Node) {
            when (node) {
                is TextNode -> append(node.wholeText.replace('\u00a0', ' '))
                is Element -> {
                    if (node.normalName() == "br") {
                        append('\n')
                    } else {
                        val isBlock = node.isBlock && node !== body
                        if (isBlock && isNotEmpty() && last() != '\n') append('\n')
                        val contentStart = length
                        node.childNodes().forEach(::appendNode)
                        if (isBlock && node.nextSibling() != null && needsLineBreakAfter(contentStart)) append('\n')
                    }
                }
            }
        }
        appendNode(body)
    }
}

private fun StringBuilder.needsLineBreakAfter(contentStart: Int): Boolean = length == contentStart || last() != '\n'
