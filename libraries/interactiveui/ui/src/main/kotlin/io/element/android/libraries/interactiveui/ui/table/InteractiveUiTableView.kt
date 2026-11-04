/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.ui.table

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.swipe.SwipeSensitivity
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.utils.BlockAppDrawerGestures
import io.element.android.libraries.interactiveui.ui.R
import kotlinx.collections.immutable.ImmutableList

/** Ported from element-web packages/shared-components/src/room/timeline/event-tile/body/InteractiveUiView/table/InteractiveUiTableView.tsx
 * at bcb08017 (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial).
 * [sort] is the caller-owned column key and descending flag; sorting includes all rows before truncation.
 */
@Composable
fun InteractiveUiTableView(
    caption: String?,
    columns: ImmutableList<InteractiveUiTableColumn>,
    rows: ImmutableList<ImmutableList<InteractiveUiCell>>,
    sort: Pair<String, Boolean>?,
    sortable: Boolean,
    enabled: Boolean,
    onSort: (columnKey: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAll by rememberSaveable(rows) { mutableStateOf(false) }
    val indices = remember(rows, columns, sort) {
        val index = columns.indexOfFirst { it.key == sort?.first }
        if (index >= 0 && sort != null) sortedRowIndices(rows, index, columns[index].numeric, sort.second) else rows.indices.toList()
    }
    val scroll = rememberScrollState()
    if (scroll.isScrollInProgress) BlockAppDrawerGestures()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(caption ?: stringResource(R.string.screen_interactive_ui_data_table), Modifier.semantics { heading() })
        SwipeSensitivity(1f / 3f) {
            Column(Modifier.horizontalScroll(scroll).semantics { collectionInfo = CollectionInfo(rows.size + 1, columns.size) }) {
                Row {
                    columns.forEach { column ->
                        val sorted = sort?.first == column.key
                        val description = stringResource(
                            if (!sorted) {
                                R.string.a11y_interactive_ui_not_sorted
                            } else if (sort.second) {
                                R.string.a11y_interactive_ui_sorted_descending
                            } else {
                                R.string.a11y_interactive_ui_sorted_ascending
                            }
                        )
                        Text(
                            column.label + if (sorted) {
                                if (sort.second) {
                                    " ↓"
                                } else {
                                    " ↑"
                                }
                            } else {
                                ""
                            },
                            Modifier.width(160.dp).heightIn(min = 48.dp).semantics {
                                heading()
                                stateDescription = description
                            }.then(if (sortable) Modifier.clickable(enabled = enabled, role = Role.Button) { onSort(column.key) } else Modifier)
                                .padding(8.dp),
                            style = ElementTheme.typography.fontBodyMdMedium,
                            textAlign = if (column.alignEnd) TextAlign.End else TextAlign.Start,
                        )
                    }
                }
                indices.take(if (showAll) rows.size else 50).forEachIndexed { rowIndex, originalIndex ->
                    Row {
                        columns.forEachIndexed { columnIndex, column ->
                            Text(
                                rows[originalIndex].getOrElse(columnIndex) { InteractiveUiCell.Empty }.display(),
                                Modifier.width(160.dp).padding(8.dp).semantics {
                                    collectionItemInfo = CollectionItemInfo(rowIndex + 1, 1, columnIndex, 1)
                                },
                                textAlign = if (column.alignEnd) TextAlign.End else TextAlign.Start,
                            )
                        }
                    }
                }
            }
        }
        if (!showAll && rows.size > 50) {
            Text(
                pluralStringResource(R.plurals.screen_interactive_ui_show_all_rows, rows.size, rows.size),
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { showAll = true }.padding(8.dp),
                color = ElementTheme.colors.textActionPrimary,
            )
        }
    }
}
