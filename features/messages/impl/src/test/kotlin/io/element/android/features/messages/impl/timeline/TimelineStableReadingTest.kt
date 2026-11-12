/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.messages.impl.timeline

import android.graphics.Canvas
import android.text.SpannableString
import android.text.SpannedString
import android.text.style.AbsoluteSizeSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.messages.impl.timeline.model.NewEventState
import io.element.android.features.messages.impl.timeline.model.event.AnswerNavigation
import io.element.android.features.messages.impl.timeline.model.event.AnswerSection
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemTextContent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemImageContent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemTextContent
import io.element.android.features.messages.impl.timeline.protection.aTimelineProtectionState
import io.element.android.libraries.designsystem.utils.LocalUiTestMode
import io.element.android.libraries.maths.api.MathsLayout
import io.element.android.libraries.maths.api.MathsSpan
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.setSafeContent
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TimelineStableReadingTest : RobolectricTest() {
    @Test(timeout = 30_000)
    fun `native growing text keeps earlier passage in place`() = verifyGrowingText(thread = false)

    @Test(timeout = 30_000)
    fun `thread native growing text keeps earlier passage in place`() = verifyGrowingText(thread = true)

    private fun verifyGrowingText(thread: Boolean) = runAndroidComposeUiTest {
        val fixture = Fixture(thread)
        setTimeline(fixture)
        runOnIdle { assertTrue(fixture.row().size > fixture.list.layoutInfo.viewportSize.height) }
        runOnIdle { runBlocking { fixture.list.scrollToItem(0, 600) } }
        val before = runOnIdle { fixture.textStart() }
        repeat(3) { edit ->
            runOnIdle { fixture.replaceAnswer(50 + edit * 10) }
            runOnIdle { assertEquals("Earlier text must stay anchored on edit $edit", before, fixture.textStart()) }
        }
        runOnIdle { fixture.appendChunk() }
        runOnIdle { assertEquals("Independent continuation must not pull the reader", before, fixture.textStart()) }
        runOnIdle { fixture.appendChunk(otherSender = true) }
        runOnIdle { assertEquals("Other sender must not pull the reader", before, fixture.textStart()) }
        runOnIdle { runBlocking { fixture.list.scrollToItem(2, 1000) } }
        val manuallyMoved = runOnIdle { fixture.textStart() }
        runOnIdle { fixture.replaceAnswer(90) }
        runOnIdle { assertEquals("Manual scrolling establishes a new reading position", manuallyMoved, fixture.textStart()) }
        onNodeWithContentDescription("Jump to bottom").performClick()
        runOnIdle { assertTrue("Returning to latest reaches the bottom", !fixture.list.canScrollBackward) }
        runOnIdle { fixture.appendChunk() }
        runOnIdle { assertTrue("Returning to latest resumes follow", !fixture.list.canScrollBackward) }
    }

    @Test(timeout = 30_000)
    fun `reading growing answer tail wins over a newer visible event`() = runAndroidComposeUiTest {
        val fixture = Fixture()
        fixture.appendChunk(otherSender = true)
        fixture.newEvent.value = NewEventState.None
        setTimeline(fixture)
        runOnIdle { runBlocking { fixture.list.scrollToItem(0, 30) } }
        val before = runOnIdle {
            assertEquals("Both the answer and newer event must be visible", 2, fixture.list.layoutInfo.visibleItemsInfo.size)
            fixture.textStart()
        }
        runOnIdle { fixture.replaceAnswer(50) }
        runOnIdle { assertEquals("The top reading event wins over the later event", before, fixture.textStart()) }
    }

    @Test(timeout = 30_000)
    fun `reading event survives a newer event growing beyond the lazy window`() = runAndroidComposeUiTest {
        val fixture = Fixture()
        fixture.appendChunk(otherSender = true)
        fixture.newEvent.value = NewEventState.None
        setTimeline(fixture)
        runOnIdle { runBlocking { fixture.list.scrollToItem(0, 30) } }
        val before = runOnIdle { fixture.textStart() }
        runOnIdle {
            fixture.items.value = fixture.items.value.map { if (it.id != fixture.answer.id) it.copy(content = content(80)) else it }
        }
        runOnIdle { assertEquals("Restore the reading event even after it leaves the measured window", before, fixture.textStart()) }
    }

    @Test(timeout = 30_000)
    fun `removing reading event captures a surviving event`() = runAndroidComposeUiTest {
        val fixture = Fixture()
        fixture.appendChunk(otherSender = true)
        fixture.newEvent.value = NewEventState.None
        setTimeline(fixture)
        runOnIdle { runBlocking { fixture.list.scrollToItem(0, 30) } }
        runOnIdle { fixture.items.value = fixture.items.value.filter { it.id != fixture.answer.id } }
        runOnIdle {
            assertTrue(fixture.list.layoutInfo.visibleItemsInfo.isNotEmpty())
            assertTrue(fixture.list.layoutInfo.visibleItemsInfo.none { it.key == fixture.answer.id })
        }
    }

    @Test(timeout = 30_000)
    fun `native bottom follow survives growing same-event text`() = runAndroidComposeUiTest {
        val fixture = Fixture()
        setTimeline(fixture)
        repeat(3) { edit ->
            runOnIdle { fixture.replaceAnswer(40 + edit * 10) }
            runOnIdle { assertTrue(!fixture.list.canScrollBackward) }
        }
    }

    @Test(timeout = 30_000)
    fun `late maths metrics above reading line preserve that line`() = verifySpanResize(maths = true)

    @Test(timeout = 30_000)
    fun `late native span size change above reading line preserves that line`() = verifySpanResize(maths = false)

    private fun verifySpanResize(maths: Boolean) = runAndroidComposeUiTest {
        val fixture = Fixture()
        setTimeline(fixture)
        runOnIdle { runBlocking { fixture.list.scrollToItem(0, 600) } }
        val start = runOnIdle { fixture.textStart() }
        val beforeSize = runOnIdle { fixture.row().size }
        runOnIdle {
            val text = SpannableString((fixture.answer.content as TimelineItemTextContent).body)
            val span = if (maths) {
                MathsSpan("synthetic", object : MathsLayout {
                    override val widthEm = 2f
                    override val heightEm = 12f
                    override val depthEm = 1f
                    override fun draw(canvas: Canvas, fontSizePx: Float, color: Int) = Unit
                }, displayMode = true)
            } else {
                AbsoluteSizeSpan(90)
            }
            text.setSpan(span, 0, text.indexOf('\n'), 0)
            fixture.items.value = fixture.items.value.map {
                if (it.id == fixture.answer.id) it.copy(content = content(30).copy(formattedBody = text)) else it
            }
        }
        runOnIdle {
            val growth = fixture.row().size - beforeSize
            assertTrue("The delayed span must change actual native line metrics", growth > 0)
            assertEquals("Reading line below the resized span stays fixed", start, fixture.textStart() - growth)
        }
    }

    @Test(timeout = 30_000)
    fun `late image dimensions preserve native caption passage`() = runAndroidComposeUiTest {
        val fixture = Fixture()
        val image = aTimelineItemImageContent(caption = content(30).body, aspectRatio = 4f)
        fixture.items.value = listOf(fixture.answer.copy(content = image))
        setTimeline(fixture)
        runOnIdle { runBlocking { fixture.list.scrollToItem(0, 600) } }
        val start = runOnIdle { fixture.textStart() }
        val beforeSize = runOnIdle { fixture.row().size }
        runOnIdle { fixture.items.value = listOf(fixture.answer.copy(content = image.copy(aspectRatio = 0.5f))) }
        runOnIdle {
            val growth = fixture.row().size - beforeSize
            assertTrue("Image container must resize", growth > 0)
            assertEquals("Caption below the resized media keeps its position", start, fixture.textStart() - growth)
        }
    }

    @Test(timeout = 30_000)
    fun `room structured fragments preserve reading across edits and answer removal`() = verifyStructuredFragments(thread = false)

    @Test(timeout = 30_000)
    fun `thread structured fragments preserve reading across edits and answer removal`() = verifyStructuredFragments(thread = true)

    private fun verifyStructuredFragments(thread: Boolean) = runAndroidComposeUiTest {
        val fixture = Fixture(thread)
        val readingBody = (0 until 20).joinToString("\n\n") { "Reading fragment passage $it: unchanged text." }
        fun structured(earlier: Int, later: Int) = content(1).copy(answerNavigation = AnswerNavigation(
            introduction = SpannedString("Introduction"),
            sections = listOf(
                AnswerSection("Evidence", 2, SpannedString("Evidence"), content(earlier).formattedBody),
                AnswerSection("Evidence", 2, SpannedString("Evidence"), SpannedString(readingBody)),
                AnswerSection("Evidence", 2, SpannedString("Evidence"), content(later).formattedBody),
            ).toImmutableList(),
            sources = persistentListOf(),
        ))
        fixture.items.value = listOf(fixture.answer.copy(content = structured(20, 20)))
        setTimeline(fixture)
        fun readingTop() = nativeTextViews(activity!!.window.decorView)
            .last { it.text.toString() == readingBody && it.isAttachedToWindow }
            .let { view -> IntArray(2).also(view::getLocationOnScreen)[1] }
        val initialTop = runOnIdle { readingTop() }
        runOnIdle { runBlocking { fixture.list.scrollBy(30f - initialTop) } }
        val before = runOnIdle {
            assertEquals("Place the middle fragment at the reading position", 30, readingTop())
            readingTop()
        }
        runOnIdle { fixture.items.value = listOf(fixture.answer.copy(content = structured(40, 20))) }
        runOnIdle { assertEquals("Earlier fragment growth must not move the reading fragment", before, readingTop()) }
        runOnIdle { fixture.items.value = listOf(fixture.answer.copy(content = structured(40, 40))) }
        runOnIdle { assertEquals("Later fragment growth must not move the reading fragment", before, readingTop()) }
        runOnIdle { fixture.appendChunk(otherSender = true) }
        runOnIdle { assertEquals("A later event must preserve the structured reading position", before, readingTop()) }
        runOnIdle {
            fixture.items.value = fixture.items.value.map {
                if (it.id == fixture.answer.id) it.copy(content = aTimelineItemTextContent("Short", formattedBody = SpannedString("Short"))) else it
            }
        }
        runOnIdle {
            assertTrue(
                "An edit can remove all fragments and restore the ordinary body",
                nativeTextViews(activity!!.window.decorView)
                .any { it.text.toString() == "Short" && it.isAttachedToWindow }
            )
        }
    }

    private fun nativeTextViews(view: View): List<TextView> = buildList {
        if (view is TextView) add(view)
        if (view is ViewGroup) repeat(view.childCount) { addAll(nativeTextViews(view.getChildAt(it))) }
    }

    private class Fixture(thread: Boolean = false) {
        val answer = aTimelineItemEvent(content = content(30))
        val items = mutableStateOf(listOf(answer))
        val newEvent = mutableStateOf<NewEventState>(NewEventState.None)
        val list = LazyListState()
        val mode = if (thread) Timeline.Mode.Thread(ThreadId("\$root")) else Timeline.Mode.Live

        fun row() = list.layoutInfo.visibleItemsInfo.single { it.key == answer.id }

        // In reverseLayout, offset + size measures the physical top from the bottom.
        fun textStart() = row().offset + row().size
        fun replaceAnswer(count: Int) {
            items.value = items.value.map { if (it.id == answer.id) it.copy(content = content(count).copy(isEdited = true)) else it }
        }
        fun appendChunk(otherSender: Boolean = false) {
            val chunk = aTimelineItemEvent(content = content(1)).let {
                if (otherSender) it.copy(senderId = UserId("@other:synthetic.test")) else it
            }
            items.value = listOf(chunk) + items.value
            newEvent.value = NewEventState.FromOther
        }
    }

    private fun AndroidComposeUiTest<ComponentActivity>.setTimeline(fixture: Fixture) {
        setSafeContent(clearAndroidUiDispatcher = true) {
            // Inspection disables native Rust conversion; SpannedString still uses the real Android
            // TextView/Layout. Ordinary Compose tests skip this renderer via LocalUiTestMode.
            CompositionLocalProvider(LocalUiTestMode provides false, LocalInspectionMode provides true) {
                ElementTheme {
                    TimelineView(
                        state = aTimelineState(
                            timelineItems = fixture.items.value.toImmutableList(),
                            newEventState = fixture.newEvent.value,
                            timelineMode = fixture.mode,
                            isLive = true,
                            useNewTimelineEventRenderer = false,
                        ),
                        timelineProtectionState = aTimelineProtectionState(),
                        lazyListState = fixture.list,
                        modifier = Modifier.height(300.dp),
                        onUserDataClick = {},
                        onLinkClick = {},
                        onContentClick = {},
                        onGalleryItemClick = { _, _ -> },
                        onMessageLongClick = {},
                        onSwipeToReply = {},
                        onReactionClick = { _, _ -> },
                        onReactionLongClick = { _, _ -> },
                        onMoreReactionsClick = {},
                        onReadReceiptClick = {},
                        onJoinCallClick = {},
                    )
                }
            }
        }
    }

    companion object {
        private fun content(count: Int) = aTimelineItemTextContent(
            body = (0 until count).joinToString("\n\n") { "Passage $it: synthetic reading evidence." },
        ).let { it.copy(formattedBody = SpannedString(it.body)) }
    }
}
