/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.threads.impl

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.element.android.libraries.designsystem.atomic.molecules.IconTitlePlaceholdersRowMolecule

@Composable
internal fun ThreadsInboxLoading(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "inbox placeholders")
    val progress by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer position",
    )
    Column(modifier) {
        repeat(3) { index ->
            IconTitlePlaceholdersRowMolecule(
                iconSize = 52.dp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("inbox-placeholder-$index")
                    .drawWithContent {
                        drawContent()
                        val start = size.width * progress
                        drawRect(Brush.linearGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = 0.35f), Color.Transparent),
                            start = Offset(start, 0f),
                            end = Offset(start + size.width / 2, size.height),
                        ))
                    },
            )
        }
    }
}
