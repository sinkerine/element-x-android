/*
 * Copyright 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components.event

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.messages.impl.R
import io.element.android.features.messages.impl.timeline.LocalTimelineEventRendererConfig
import io.element.android.features.messages.impl.timeline.model.event.AnswerNavigation
import io.element.android.features.messages.impl.timeline.model.event.AnswerSection
import io.element.android.features.messages.impl.timeline.model.event.AnswerSource
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemTextContent
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.ModalBottomSheet
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.ui.common.layout.ContentAvoidingLayoutData
import io.element.android.wysiwyg.link.Link
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnswerNavigationView(
    navigation: AnswerNavigation,
    onLinkClick: (Link) -> Unit,
    onLinkLongClick: (Link) -> Unit,
    modifier: Modifier = Modifier,
    onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit = {},
) {
    var isOpen by remember { mutableStateOf(false) }
    var currentSection by remember { mutableStateOf<Int?>(null) }
    val targets = remember(navigation) { navigation.sections.map { BringIntoViewRequester() } }
    val scope = rememberCoroutineScope()
    val viewportHeight = LocalTimelineEventRendererConfig.current.viewportHeight
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    Column(
        modifier.onSizeChanged {
            // A multi-block answer reserves a separate timestamp line, rather than measuring only its last fragment.
            onContentLayoutChange(ContentAvoidingLayoutData(it.width, it.height, it.width, it.height))
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = {
            currentSection = null
            isOpen = true
        }) {
            Text(stringResource(R.string.screen_answer_navigation_title), color = ElementTheme.colors.textLinkExternal)
        }
        AnswerFragment(navigation.introduction, onLinkClick, onLinkLongClick, readingKey = "answer:introduction")
        navigation.sections.forEachIndexed { index, section ->
            Row(
                Modifier.fillMaxWidth().bringIntoViewRequester(targets[index]),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnswerFragment(
                    section.heading,
                    onLinkClick,
                    onLinkLongClick,
                    readingKey = "answer:section:$index:heading",
                    modifier = Modifier.weight(1f),
                    style = if (section.level <= 2) ElementTheme.typography.fontHeadingMdBold else ElementTheme.typography.fontHeadingSmMedium,
                )
                TextButton(onClick = {
                    currentSection = index
                    isOpen = true
                }) {
                    Text(
                        stringResource(R.string.screen_answer_navigation_sections),
                        color = ElementTheme.colors.textLinkExternal,
                        style = ElementTheme.typography.fontBodySmMedium,
                    )
                }
            }
            AnswerFragment(section.body, onLinkClick, onLinkLongClick, readingKey = "answer:section:$index:body")
        }
    }
    if (isOpen) {
        ModalBottomSheet(onDismissRequest = { isOpen = false }, scrollable = true, sheetState = sheetState) {
            AnswerIndexContent(
                navigation = navigation,
                currentSection = currentSection,
                onSectionClick = { index ->
                    scope.launch {
                        sheetState.hide()
                        isOpen = false
                        currentSection = index
                        // Reserve most of the current viewport below the heading, so forward jumps also reveal the passage.
                        val passageHeight = viewportHeight() * 0.9f
                        targets[index].bringIntoView(if (passageHeight > 0f) Rect(0f, 0f, 1f, passageHeight) else null)
                    }
                },
                onLinkClick = { link ->
                    scope.launch {
                        sheetState.hide()
                        isOpen = false
                        onLinkClick(link)
                    }
                },
            )
        }
    }
}

@Composable
private fun AnswerFragment(
    text: CharSequence,
    onLinkClick: (Link) -> Unit,
    onLinkLongClick: (Link) -> Unit,
    readingKey: String,
    modifier: Modifier = Modifier,
    style: TextStyle? = null,
) {
    if (text.isNotEmpty()) {
        TimelineItemTextView(
            content = remember(text) { TimelineItemTextContent("", null, text, false) },
            onLinkClick = onLinkClick,
            onLinkLongClick = onLinkLongClick,
            modifier = modifier,
            textStyleOverride = style,
            readingKey = readingKey,
        )
    }
}

@Composable
internal fun AnswerIndexContent(
    navigation: AnswerNavigation,
    currentSection: Int?,
    onSectionClick: (Int) -> Unit,
    onLinkClick: (Link) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(stringResource(R.string.screen_answer_navigation_title), style = ElementTheme.typography.fontHeadingSmMedium)
        navigation.sections.forEachIndexed { index, section ->
            Row(
                Modifier.fillMaxWidth()
                    .background(if (currentSection == index) ElementTheme.colors.bgSubtleSecondary else ElementTheme.colors.bgCanvasDefault)
                    .clickable { onSectionClick(index) }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("${index + 1}.", color = ElementTheme.colors.textSecondary)
                Text(section.title, modifier = Modifier.weight(1f))
                if (currentSection == index) {
                    Text(stringResource(R.string.screen_answer_navigation_current), color = ElementTheme.colors.textSecondary)
                }
            }
        }
        if (navigation.sources.isNotEmpty()) {
            Text(
                stringResource(R.string.screen_answer_navigation_sources),
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                style = ElementTheme.typography.fontBodyMdMedium,
            )
            navigation.sources.forEach { source ->
                Column(
                    Modifier.fillMaxWidth().clickable { onLinkClick(Link(url = source.url, text = source.label)) }
                        .padding(vertical = 12.dp, horizontal = 8.dp)
                ) {
                    Text(source.label, color = ElementTheme.colors.textLinkExternal)
                    Text(source.host, style = ElementTheme.typography.fontBodySmRegular, color = ElementTheme.colors.textSecondary)
                }
            }
        }
    }
}

internal class AnswerNavigationPreviewProvider : PreviewParameterProvider<AnswerNavigation> {
    private val navigation = AnswerNavigation(
        introduction = "A structured answer.",
        sections = persistentListOf(
            AnswerSection("Summary", 2, "Summary", "A brief conclusion."),
            AnswerSection("Evidence", 2, "Evidence", "Supporting detail."),
            AnswerSection("Evidence", 2, "Evidence", "Another passage with the same heading."),
        ),
        sources = persistentListOf(AnswerSource("Matrix client–server specification", "https://spec.matrix.org", "spec.matrix.org")),
    )
    override val values = sequenceOf(navigation, navigation.copy(sources = persistentListOf()))
}

@PreviewsDayNight
@Composable
internal fun AnswerIndexPreview(@PreviewParameter(AnswerNavigationPreviewProvider::class) navigation: AnswerNavigation) = ElementPreview {
    AnswerIndexContent(navigation, 2, {}, {})
}

@PreviewsDayNight
@Composable
internal fun AnswerNavigationPreview(@PreviewParameter(AnswerNavigationPreviewProvider::class) navigation: AnswerNavigation) = ElementPreview {
    AnswerNavigationView(navigation, {}, {})
}
