/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.factories.event

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.fixtures.aTimelineItemContentFactory
import io.element.android.features.messages.impl.interactiveui.interactiveUiEvent
import io.element.android.features.messages.impl.interactiveui.interactiveUiFallback
import io.element.android.features.messages.impl.interactiveui.interactiveUiPayloadJson
import io.element.android.features.messages.impl.timeline.model.event.InteractiveUiTimelineModel
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemInteractiveUiContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemRedactedContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemTextContent
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.featureflag.test.FakeFeatureFlagService
import io.element.android.libraries.htmlrenderer.api.DocumentNode
import io.element.android.libraries.htmlrenderer.test.FakeHtmlMessageParser
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiErrorCode
import io.element.android.libraries.matrix.api.timeline.item.event.NoticeMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.TimelineItemDebugInfoProvider
import io.element.android.libraries.matrix.test.permalink.FakePermalinkParser
import io.element.android.libraries.matrix.test.timeline.aMessageContent
import io.element.android.libraries.matrix.test.timeline.aRedactedContent
import io.element.android.libraries.matrix.test.timeline.aTimelineItemDebugInfo
import io.element.android.libraries.matrix.test.timeline.anEventTimelineItem
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jsoup.nodes.Document
import org.junit.Test

class TimelineItemContentInteractiveUiFactoryTest {
    @Test
    fun `flag off local and non-text never read debug info`() = runTest {
        var calls = 0
        val flags = FakeFeatureFlagService()
        val factory =
            TimelineItemContentInteractiveUiFactory(
                flags,
                FakePermalinkParser {
                    io.element.android.libraries.matrix.api.permalink.PermalinkData.UserLink(
                    io.element.android.libraries.matrix.api.core.UserId("@linked:test")
                )
                },
                FakeHtmlMessageParser(),
                testCoroutineDispatchers()
            )
        val item = anEventTimelineItem(isRemote = true, content = aMessageContent(), debugInfoProvider = TimelineItemDebugInfoProvider {
            calls++
            aTimelineItemDebugInfo(originalJson = interactiveUiEvent())
        })
        assertThat(factory.create(item, interactiveUiFallback)).isNull()
        flags.setFeatureEnabled(FeatureFlags.InteractiveUi, true)
        assertThat(factory.create(item.copy(isRemote = false), interactiveUiFallback)).isNull()
        assertThat(factory.create(item.copy(content = aMessageContent(messageType = NoticeMessageType("notice", null))), interactiveUiFallback)).isNull()
        assertThat(factory.create(item.copy(eventId = null), interactiveUiFallback)).isNull()
        assertThat(calls).isEqualTo(0)
        val content = factory.create(item, interactiveUiFallback)!!
        assertThat(calls).isEqualTo(1)
        assertThat(content.model).isInstanceOf(InteractiveUiTimelineModel.Valid::class.java)
        assertThat(content.body).isEqualTo(interactiveUiFallback.body)
        assertThat(content.plainText).isEqualTo(interactiveUiFallback.plainText)
        assertThat(content.formattedBody).isEqualTo(interactiveUiFallback.formattedBody)
    }

    @Test
    fun `real factory overload decodes remote timeline but reply preview overload stays text`() = runTest {
        val interactive = TimelineItemContentInteractiveUiFactory(
            FakeFeatureFlagService(mapOf(FeatureFlags.InteractiveUi.key to true)),
            FakePermalinkParser(),
            FakeHtmlMessageParser(),
            testCoroutineDispatchers(),
        )
        val factory = aTimelineItemContentFactory(interactiveUiFactory = interactive)
        val item = anEventTimelineItem(
            isRemote = true,
            content = aMessageContent(),
            debugInfoProvider = TimelineItemDebugInfoProvider { aTimelineItemDebugInfo(originalJson = interactiveUiEvent()) }
        )
        assertThat(factory.create(item, emptyList())).isInstanceOf(TimelineItemInteractiveUiContent::class.java)
        assertThat(factory.create(item.content, item.eventId, false, item.sender, item.senderProfile)).isInstanceOf(TimelineItemTextContent::class.java)
    }

    @Test
    fun `no key malformed JSON and debug errors fall back without throwing`() = runTest {
        val factory = TimelineItemContentInteractiveUiFactory(
            FakeFeatureFlagService(mapOf(FeatureFlags.InteractiveUi.key to true)),
            FakePermalinkParser {
                io.element.android.libraries.matrix.api.permalink.PermalinkData.UserLink(
                io.element.android.libraries.matrix.api.core.UserId("@linked:test")
            )
            },
            FakeHtmlMessageParser(),
            testCoroutineDispatchers()
        )
        for (json in listOf(interactiveUiEvent(null), "{", interactiveUiEvent().replace("m.room.message", "m.room.topic"))) {
            val item = anEventTimelineItem(
                isRemote = true,
                content = aMessageContent(),
                debugInfoProvider = TimelineItemDebugInfoProvider { aTimelineItemDebugInfo(originalJson = json) }
            )
            assertThat(factory.create(item, interactiveUiFallback)).isNull()
        }
        val throwing = anEventTimelineItem(
            isRemote = true,
            content = aMessageContent(),
            debugInfoProvider = TimelineItemDebugInfoProvider { error("SDK unavailable") }
        )
        assertThat(factory.create(throwing, interactiveUiFallback)).isNull()
    }

    @Test
    fun `HTML parser failure keeps original text fallback`() = runTest {
        val factory = TimelineItemContentInteractiveUiFactory(
            FakeFeatureFlagService(mapOf(FeatureFlags.InteractiveUi.key to true)),
            FakePermalinkParser(),
            FakeHtmlMessageParser { throw IllegalStateException("Parser unavailable") },
            testCoroutineDispatchers()
        )
        val payload = """{"version":1,"ui_id":"rich","revision":0,"status":"active","state":{},"root":"rich","nodes":[
            {"id":"rich","type":"rich_text","text":"Fallback","html":"<b>Rich</b>"}]}""".trimIndent()
        val item = anEventTimelineItem(
            isRemote = true,
            content = aMessageContent(),
            debugInfoProvider = TimelineItemDebugInfoProvider { aTimelineItemDebugInfo(originalJson = interactiveUiEvent(payload)) }
        )
        assertThat(factory.create(item, interactiveUiFallback)).isNull()
    }

    @Test
    fun `invalid caption excludes unsupported version`() = runTest {
        val factory = TimelineItemContentInteractiveUiFactory(
            FakeFeatureFlagService(mapOf(FeatureFlags.InteractiveUi.key to true)),
            FakePermalinkParser {
                io.element.android.libraries.matrix.api.permalink.PermalinkData.UserLink(
                io.element.android.libraries.matrix.api.core.UserId("@linked:test")
            )
            },
            FakeHtmlMessageParser(),
            testCoroutineDispatchers()
        )
        for ((payload, code, caption) in listOf(
                Triple("{}", InteractiveUiErrorCode.BadVersion, false),
            Triple(interactiveUiPayloadJson.replace("\"version\":1", "\"version\":2"), InteractiveUiErrorCode.BadVersion, false),
            Triple(interactiveUiPayloadJson.replace("\"root\":\"root\"", "\"root\":\"missing\""), InteractiveUiErrorCode.UnknownRef, true)
            )) {
            val item = anEventTimelineItem(
                isRemote = true,
                content = aMessageContent(),
                debugInfoProvider = TimelineItemDebugInfoProvider { aTimelineItemDebugInfo(originalJson = interactiveUiEvent(payload)) }
            )
            val model = factory.create(item, interactiveUiFallback)!!.model as InteractiveUiTimelineModel.Invalid
            assertThat(model.code).isEqualTo(code)
            assertThat(model.showCaption).isEqualTo(caption)
        }
    }

    @Test
    fun `latest edit replaces payload and edit without payload drops interactive rendering`() = runTest {
        val factory = TimelineItemContentInteractiveUiFactory(
            FakeFeatureFlagService(mapOf(FeatureFlags.InteractiveUi.key to true)),
            FakePermalinkParser {
                io.element.android.libraries.matrix.api.permalink.PermalinkData.UserLink(
                io.element.android.libraries.matrix.api.core.UserId("@linked:test")
            )
            },
            FakeHtmlMessageParser(),
            testCoroutineDispatchers()
        )
        fun item(payload: String?) = anEventTimelineItem(
            isRemote = true,
            content = aMessageContent(isEdited = true),
            debugInfoProvider = TimelineItemDebugInfoProvider {
                aTimelineItemDebugInfo(
                    originalJson = interactiveUiEvent(),
                latestEditedJson = """{"type":"m.room.message","content":{"m.new_content":${Json.parseToJsonElement(
                    interactiveUiEvent(payload)
                ).jsonObject["content"]}}}"""
                )
            }
        )
        val edited = factory.create(item(interactiveUiPayloadJson.replace("\"revision\":0", "\"revision\":2")), interactiveUiFallback)!!
        assertThat((edited.model as InteractiveUiTimelineModel.Valid).payload.revision).isEqualTo(2L)
        assertThat(factory.create(item(null), interactiveUiFallback)).isNull()
        val baseFactory = aTimelineItemContentFactory()
        val remote = item(null)
        assertThat(baseFactory.create(remote, emptyList())).isInstanceOf(TimelineItemTextContent::class.java)
        assertThat(baseFactory.create(remote.copy(content = aRedactedContent()), emptyList())).isEqualTo(TimelineItemRedactedContent)
    }

    @Test
    fun `rich text always sanitizes and unwraps links and disclosures and maps encrypted media`() = runTest {
        var parsed: Document? = null
        val factory = TimelineItemContentInteractiveUiFactory(
            FakeFeatureFlagService(mapOf(FeatureFlags.InteractiveUi.key to true)),
            FakePermalinkParser {
                io.element.android.libraries.matrix.api.permalink.PermalinkData.UserLink(
                io.element.android.libraries.matrix.api.core.UserId("@linked:test")
            )
            },
            FakeHtmlMessageParser {
                parsed = it
                DocumentNode(persistentListOf())
            },
            testCoroutineDispatchers()
        )
        val html = "<p><a href='https://example.org'>link</a><details><summary>title</summary>body</details>" +
            "<script>alert(1)</script><b>bold</b><img src='https://bad'></p>"
        val payload = """{"version":1,"ui_id":"media","revision":0,"status":"active","state":{},"root":"root","nodes":[
            {"id":"root","type":"stack","direction":"vertical","children":["rich","plain","encrypted"]},
            {"id":"rich","type":"rich_text","text":"Rich","html":"$html"},
            {"id":"plain","type":"image","alt":"Plain","url":"mxc://example.org/plain","mimetype":"image/png"},
            {"id":"encrypted","type":"image","alt":"Encrypted","file":{"url":"mxc://example.org/encrypted",
                "key":{"kty":"oct","key_ops":["encrypt","decrypt"],"alg":"A256CTR","k":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","ext":true},
                "iv":"AAAAAAAAAAAAAAAAAAAAAA","hashes":{"sha256":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"},"v":"v2"},"mimetype":"image/png"}]}
        """.trimIndent()
        val item = anEventTimelineItem(
            isRemote = true,
            content = aMessageContent(),
            debugInfoProvider = TimelineItemDebugInfoProvider { aTimelineItemDebugInfo(originalJson = interactiveUiEvent(payload)) }
        )
        val result = factory.create(item, interactiveUiFallback)!!.model
        assertThat(result).isInstanceOf(InteractiveUiTimelineModel.Valid::class.java)
        val model = result as InteractiveUiTimelineModel.Valid
        val document = checkNotNull(parsed)
        assertThat(document.select("a, details, summary, script, img")).isEmpty()
        assertThat(document.text()).contains("link")
        assertThat(document.select("b").text()).isEqualTo("bold")
        assertThat(model.richText.keys).containsExactly("rich")
        assertThat(model.media.getValue("plain").safeUrl).isEqualTo("mxc://example.org/plain")
        assertThat(model.media.getValue("plain").json).isNull()
        val encrypted = model.media.getValue("encrypted")
        assertThat(encrypted.safeUrl).isEqualTo("mxc://example.org/encrypted")
        assertThat(Json.parseToJsonElement(encrypted.json!!).jsonObject.getValue("file").jsonObject.getValue("v").toString()).isEqualTo("\"v2\"")
    }
}
