/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.messagecomposer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ComposerPlainTextTest {
    @Test
    fun `visible text preserves identifiers punctuation and line breaks`() {
        val html = """<p>/model <a href="https://matrix.to/#/@alice:server.org">@alice:server.org</a><br>""" +
            """<a href="https://matrix.to/#/%23room:server.org">#room:server.org</a> @room</p><p>a_b &amp; *c*</p>"""
        assertThat(htmlToComposerPlainText(html)).isEqualTo("/model @alice:server.org\n#room:server.org @room\na_b & *c*")
    }

    @Test
    fun `nested blocks and explicit empty lines retain boundaries`() {
        assertThat(htmlToComposerPlainText("<div>one<div>two</div>three</div><p>four<br><br>five</p>"))
            .isEqualTo("one\ntwo\nthree\nfour\n\nfive")
        assertThat(htmlToComposerPlainText("<p>one</p><p></p><p>two</p>")).isEqualTo("one\n\ntwo")
        assertThat(htmlToComposerPlainText("")).isEmpty()
        assertThat(htmlToComposerPlainText("<p>one&nbsp;two</p>")).isEqualTo("one two")
    }
}
