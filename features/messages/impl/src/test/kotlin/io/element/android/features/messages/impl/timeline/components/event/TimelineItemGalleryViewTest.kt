/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components.event

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.timeline.factories.event.MathsSpansConverter
import io.element.android.features.messages.impl.timeline.factories.event.TimelineItemContentMessageFactory
import io.element.android.features.messages.impl.timeline.model.event.GalleryItem
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemGalleryContent
import io.element.android.features.messages.test.timeline.FakeTextPillificator
import io.element.android.features.messages.test.timeline.FakeHtmlConverterProvider
import io.element.android.libraries.androidutils.filesize.FakeFileSizeFormatter
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.featureflag.test.FakeFeatureFlagService
import io.element.android.libraries.htmlrenderer.api.DocumentNode
import io.element.android.libraries.htmlrenderer.test.FakeHtmlMessageParser
import io.element.android.libraries.maths.test.FakeMathsDetector
import io.element.android.libraries.maths.test.FakeMathsLayoutEngine
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.media.FileInfo
import io.element.android.libraries.matrix.api.media.ImageInfo
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.timeline.item.event.FileMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.GalleryItemType
import io.element.android.libraries.matrix.api.timeline.item.event.GalleryMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.ImageMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.test.A_USER_ID
import io.element.android.libraries.matrix.test.permalink.FakePermalinkParser
import io.element.android.libraries.matrix.test.timeline.aProfileDetails
import io.element.android.libraries.matrix.ui.media.contentvalidation.ContentValidationValue
import io.element.android.libraries.matrix.ui.media.contentvalidation.DefaultContentValidationState
import io.element.android.libraries.matrix.ui.media.contentvalidation.LocalEventContentValidationState
import io.element.android.libraries.matrix.ui.media.contentvalidation.NoopEventContentValidationCache
import io.element.android.libraries.mediaviewer.impl.util.FileExtensionExtractorWithValidation
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class TimelineItemGalleryViewTest : RobolectricTest() {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `extensionless mixed gallery row shows size only with the production extension extractor`() = runTest {
        val extractor = FileExtensionExtractorWithValidation()
        assertThat(extractor.extractFromName("noext")).isEqualTo("bin")
        val factory = TimelineItemContentMessageFactory(
            mathsSpansConverter = MathsSpansConverter(FakeMathsLayoutEngine()),
            mathsDetector = FakeMathsDetector(),
            fileSizeFormatter = FakeFileSizeFormatter(),
            fileExtensionExtractor = extractor,
            htmlConverterProvider = FakeHtmlConverterProvider(),
            permalinkParser = FakePermalinkParser(),
            textPillificator = FakeTextPillificator(),
            htmlMessageParser = FakeHtmlMessageParser(parseResult = { DocumentNode(persistentListOf()) }),
            featureFlagService = FakeFeatureFlagService(),
        )
        val items = listOf(
            GalleryItemType.Image(ImageMessageType(
                "red.png",
                null,
                null,
                MediaSource("image"),
                ImageInfo(64, 96, "image/png", 123, null, MediaSource("thumbnail"), null)
            )),
            GalleryItemType.File(FileMessageType("noext", null, null, MediaSource("file"), FileInfo("application/octet-stream", 4, null, null))),
            GalleryItemType.File(FileMessageType("report.pdf", null, null, MediaSource("pdf"), FileInfo("application/pdf", 123, null, null))),
        )
        val content = factory.create(
            content = MessageContent("caption", null, false, null, GalleryMessageType("caption", null, items)),
            senderId = A_USER_ID,
            senderProfile = aProfileDetails(),
            eventId = null,
        ) as TimelineItemGalleryContent
        composeRule.setContent { ElementPreview { TimelineItemGalleryView(null, content, {}, {}) } }
        composeRule.onNodeWithText("noext").assertIsDisplayed()
        composeRule.onNodeWithText("4 Bytes").assertIsDisplayed()
        composeRule.onNodeWithText("bin • 4 Bytes").assertDoesNotExist()
        composeRule.onNodeWithText("pdf • 123 Bytes").assertIsDisplayed()
        val legacy = factory.create(
            content = MessageContent("caption", null, false, null, GalleryMessageType("caption", null, items.drop(1))),
            senderId = A_USER_ID,
            senderProfile = aProfileDetails(),
            eventId = null,
        ) as io.element.android.features.messages.impl.timeline.model.event.TimelineItemAttachmentsContent
        assertThat(legacy.attachments.first().fileExtension).isEqualTo("bin")
    }

    @Test
    fun `file rows follow media and thumbnail validation states`() {
        val validation = DefaultContentValidationState(mapOf(
            "media" to ContentValidationValue.Valid,
            "thumbnail" to ContentValidationValue.Valid,
        ))
        val item = aGalleryItem(filename = "report.pdf", type = GalleryItem.Type.File, mediaSource = MediaSource("media"))
            .copy(thumbnailSource = MediaSource("thumbnail"))
        composeRule.setContent {
            ElementPreview {
                CompositionLocalProvider(LocalEventContentValidationState provides NoopEventContentValidationCache(validation)) {
                    TimelineItemGalleryView(EventId("$" + "event"), aTimelineItemGalleryContent(items = listOf(aGalleryItem(), item)), {}, {})
                }
            }
        }
        composeRule.onNodeWithText("report.pdf").assertIsDisplayed()
        listOf(
            ContentValidationValue.Loading,
            ContentValidationValue.Invalid,
            ContentValidationValue.UnrecoverableError(IllegalStateException("synthetic")),
            ContentValidationValue.Valid
        ).forEach { value ->
            // Set both sources so loading is not ignored by the validation cache's valid -> loading rule.
            composeRule.runOnIdle {
                validation.update("thumbnail", ContentValidationValue.Unknown)
                validation.update("thumbnail", value)
            }
            composeRule.waitForIdle()
            if (value.isValid()) {
                composeRule.onNodeWithText("report.pdf").assertIsDisplayed()
            } else {
                composeRule.onNodeWithText("report.pdf").assertDoesNotExist()
            }
        }
    }

    @Test
    fun `mixed gallery renders visual tiles and file rows and reports item indices`() {
        val clicked = mutableListOf<Int>()
        val items = listOf(
            aGalleryItem(filename = "red.png"),
            aGalleryItem(filename = "report.pdf", type = GalleryItem.Type.File),
            aGalleryItem(filename = "tone.wav", type = GalleryItem.Type.Audio),
            aGalleryItem(filename = "notes.txt", type = GalleryItem.Type.File),
            aGalleryItem(filename = "noext", type = GalleryItem.Type.File),
        )
        composeRule.setContent {
            ElementPreview { TimelineItemGalleryView(null, aTimelineItemGalleryContent(items = items), clicked::add, {}) }
        }
        composeRule.onNodeWithContentDescription("red.png").performClick()
        composeRule.onNodeWithText("report.pdf").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("tone.wav").assertIsDisplayed()
        composeRule.onNodeWithText("notes.txt").assertIsDisplayed()
        composeRule.onNodeWithText("noext").performClick()
        assertThat(clicked).containsExactly(0, 1, 4).inOrder()
    }

    @Test
    fun `overflow counts visual items only`() {
        val items = List(7) { aGalleryItem(filename = "image$it") } +
            List(2) { aGalleryItem(filename = "file$it", type = GalleryItem.Type.File) }
        composeRule.setContent {
            ElementPreview { TimelineItemGalleryView(null, aTimelineItemGalleryContent(items = items), {}, {}) }
        }
        composeRule.onNodeWithText("+2").assertIsDisplayed()
        composeRule.onNodeWithText("+4").assertDoesNotExist()
        composeRule.onNodeWithText("file0").assertIsDisplayed()
        composeRule.onNodeWithText("file1").assertIsDisplayed()
    }

    @Test
    fun `long click on a file row invokes onLongClick`() {
        var longClicks = 0
        composeRule.setContent {
            ElementPreview {
                TimelineItemGalleryView(null, aTimelineItemGalleryContent(items = listOf(
                    aGalleryItem(),
                    aGalleryItem(filename = "report.pdf", type = GalleryItem.Type.File),
                )), {}, { longClicks++ })
            }
        }
        composeRule.onNodeWithText("report.pdf").performTouchInput { longClick() }
        assertThat(longClicks).isEqualTo(1)
    }
}
