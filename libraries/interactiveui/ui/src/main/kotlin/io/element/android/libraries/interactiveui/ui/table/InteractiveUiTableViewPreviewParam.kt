/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.ui.table

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

internal data class InteractiveUiTablePreview(
    val rows: ImmutableList<ImmutableList<InteractiveUiCell>>,
    val sort: Pair<String, Boolean>?,
    val enabled: Boolean,
)

internal class InteractiveUiTableViewPreviewParam : PreviewParameterProvider<InteractiveUiTablePreview> {
    private val rows = persistentListOf(
        persistentListOf(InteractiveUiCell.Text("DHL"), InteractiveUiCell.Number(24.0)),
        persistentListOf(InteractiveUiCell.Text("Royal Mail"), InteractiveUiCell.Number(18.5)),
    )
    override val values = sequenceOf(
        InteractiveUiTablePreview(rows, null, true),
        InteractiveUiTablePreview(rows, "price" to false, true),
        InteractiveUiTablePreview(rows, "carrier" to true, false),
        InteractiveUiTablePreview(
            (0..50).map { persistentListOf<InteractiveUiCell>(InteractiveUiCell.Text("Carrier $it"), InteractiveUiCell.Empty) }
            .toImmutableList(),
            null,
            true
        ),
    )
}

@PreviewsDayNight
@Composable
internal fun InteractiveUiTableViewPreview(@PreviewParameter(InteractiveUiTableViewPreviewParam::class) state: InteractiveUiTablePreview) {
    ElementPreview { PreviewTable(state) }
}

@Preview(fontScale = 2f, widthDp = 360)
@Composable
internal fun InteractiveUiTableLargeFontPreview() {
    ElementPreview { PreviewTable(InteractiveUiTableViewPreviewParam().values.first()) }
}

@Composable
private fun PreviewTable(state: InteractiveUiTablePreview) {
    InteractiveUiTableView("Carrier prices", persistentListOf(
        InteractiveUiTableColumn("carrier", "Carrier", false, false),
        InteractiveUiTableColumn("price", "Price (£)", true, true),
    ), state.rows, state.sort, true, state.enabled, {}, Modifier.width(360.dp))
}
