/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.theme.components.Checkbox
import io.element.android.libraries.designsystem.theme.components.DropdownMenu
import io.element.android.libraries.designsystem.theme.components.RadioButton
import io.element.android.libraries.designsystem.theme.components.Slider
import io.element.android.libraries.designsystem.theme.components.Switch
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextField
import io.element.android.libraries.designsystem.theme.components.TextFieldValidity
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiValue
import io.element.android.libraries.interactiveui.ui.R
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiRenderNode
import io.element.android.libraries.interactiveui.ui.model.InteractiveUiViewEvent
import io.element.android.libraries.interactiveui.ui.table.displayNumber
import kotlin.math.abs
import kotlin.math.round

/** Ported from element-web packages/shared-components/src/room/timeline/event-tile/body/InteractiveUiView/InteractiveUiView.tsx
 * at bcb08017 (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial).
 */
@Composable
internal fun InteractiveUiSwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled, Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
internal fun Select(node: InteractiveUiRenderNode.Select, enabled: Boolean, sink: (InteractiveUiViewEvent) -> Unit) {
    var expanded by remember(node.id) { mutableStateOf(false) }
    val placeholder = stringResource(R.string.screen_interactive_ui_choose_option)
    Box {
        TextField(
            value = node.options.firstOrNull { it.value == node.selected }?.label.orEmpty(),
            onValueChange = {},
            label = node.label,
            placeholder = placeholder,
            readOnly = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable(enabled, role = Role.DropdownList) { expanded = true }.semantics {
            contentDescription = node.label + ", " + (node.options.firstOrNull { it.value == node.selected }?.label ?: placeholder)
        })
        DropdownMenu(expanded && enabled, { expanded = false }) {
            node.options.forEach { option ->
                Text(option.label, Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(option.value == node.selected, role = Role.RadioButton) {
                    expanded = false
                    sink(InteractiveUiViewEvent.SetValue(node.id, InteractiveUiValue.Text(option.value)))
                })
            }
        }
    }
}

@Composable
internal fun Choice(node: InteractiveUiRenderNode.Choice, enabled: Boolean, sink: (InteractiveUiViewEvent) -> Unit) {
    Column(Modifier.selectableGroup()) {
        Text(node.label)
        node.options.forEach { option ->
            val selected = option.value in node.selected
            val change = {
                val value = if (node.multiple) {
                    InteractiveUiValue.TextList(if (selected) node.selected - option.value else node.selected + option.value)
                } else {
                    InteractiveUiValue.Text(option.value)
                }
                sink(InteractiveUiViewEvent.SetValue(node.id, value))
            }
            val interaction = if (node.multiple) {
                Modifier.toggleable(selected, enabled, Role.Checkbox) { change() }
            } else {
                Modifier.selectable(selected, enabled, Role.RadioButton, onClick = change)
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).then(interaction), verticalAlignment = Alignment.CenterVertically) {
                if (node.multiple) Checkbox(selected, null, enabled = enabled) else RadioButton(selected, null, enabled = enabled)
                Text(option.label)
            }
        }
    }
}

@Composable
internal fun TextInput(node: InteractiveUiRenderNode.TextInput, enabled: Boolean, sink: (InteractiveUiViewEvent) -> Unit) {
    var draft by rememberSaveable(node.id, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(node.value)) }
    var lastEmitted by remember(node.id) { mutableStateOf(node.value) }
    var observed by remember(node.id) { mutableStateOf(node.value) }
    if (node.value != observed) {
        observed = node.value
        if (node.value != lastEmitted) {
            draft = TextFieldValue(node.value)
            lastEmitted = node.value
        }
    }
    val focus = LocalFocusManager.current
    val error = stringResource(R.string.screen_interactive_ui_invalid_value)
    TextField(
        value = draft,
        onValueChange = { next ->
            if (next.text.codePointCount(0, next.text.length) <= node.maxLength) {
                draft = next
                lastEmitted = next.text
                sink(InteractiveUiViewEvent.SetValue(node.id, InteractiveUiValue.Text(next.text)))
            }
        },
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = node.label
            if (node.invalid) error(error)
        },
        label = node.label,
        placeholder = node.placeholder,
        enabled = enabled,
        validity = if (node.invalid) TextFieldValidity.Invalid else TextFieldValidity.None,
        supportingText = if (node.invalid) error else stringResource(R.string.screen_interactive_ui_required).takeIf { node.required },
        singleLine = !node.multiline,
        minLines = if (node.multiline) 3 else 1,
        maxLines = if (node.multiline) 8 else 1,
        keyboardOptions = KeyboardOptions(imeAction = if (node.multiline) ImeAction.Default else ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }, onDone = { focus.clearFocus() }),
    )
}

internal fun numberDraftValue(text: String): InteractiveUiValue {
    val number = text.replace(',', '.').toDoubleOrNull() ?: return InteractiveUiValue.Null
    if (!number.isFinite() || text.endsWith('.') || text.endsWith(',')) return InteractiveUiValue.Null
    return InteractiveUiValue.Number(number)
}

/** Only an evenly divided, finite range with at most 100 intermediate stops is discrete. */
internal fun sliderSteps(min: Double, max: Double, step: Double?): Int {
    if (!min.isFinite() || !max.isFinite() || max <= min) return 0
    if (step == null || !step.isFinite() || step <= 0) return 0
    val intervals = (max - min) / step
    return if (intervals.isFinite() && intervals in 1.0..101.0 && abs(intervals - round(intervals)) < 1e-7) round(intervals).toInt() - 1 else 0
}

/** Recover original Double endpoints/stops without rounding continuous interior values. */
internal fun sliderValue(value: Float, min: Double, max: Double, step: Double?): Double? {
    if (!value.isFinite()) return null
    if (!min.isFinite() || !max.isFinite() || max <= min) return null
    if (value <= min.toFloat()) return min
    if (value >= max.toFloat()) return max
    val steps = sliderSteps(min, max, step)
    val result = if (steps > 0 && step != null) {
        min + round((value.toDouble() - min) / step) * step
    } else {
        value.toDouble()
    }
    return result.coerceIn(min, max)
}

@Composable
internal fun NumberInput(node: InteractiveUiRenderNode.NumberInput, enabled: Boolean, sink: (InteractiveUiViewEvent) -> Unit) {
    val min = node.min ?: 0.0
    val max = node.max ?: 100.0
    // Material also subtracts the Float endpoints for touch/track calculations.
    val finiteRange = min.toFloat().isFinite() && max.toFloat().isFinite() && min.toFloat() < max.toFloat() &&
        (max.toFloat() - min.toFloat()).isFinite()
    if (node.slider && finiteRange) {
        val error = stringResource(R.string.screen_interactive_ui_invalid_value)
        Column {
            Text(node.label + ": " + displayNumber(node.value ?: min))
            Slider(
                (node.value ?: min).coerceIn(min, max).toFloat(),
                { value ->
                    sliderValue(value, min, max, node.step)?.let {
                        sink(InteractiveUiViewEvent.SetValue(node.id, InteractiveUiValue.Number(it)))
                    }
                },
                Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                    contentDescription = node.label
                    if (node.invalid) error(error)
                },
                enabled = enabled,
                valueRange = min.toFloat()..max.toFloat(),
                steps = sliderSteps(min, max, node.step),
            )
            if (node.invalid) Text(error, color = ElementTheme.colors.textCriticalPrimary, style = ElementTheme.typography.fontBodySmRegular)
        }
    } else {
        NumberTextInput(node, enabled, sink)
    }
}

@Composable
private fun NumberTextInput(node: InteractiveUiRenderNode.NumberInput, enabled: Boolean, sink: (InteractiveUiViewEvent) -> Unit) {
    var draft by rememberSaveable(node.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(node.value?.let(::displayNumber).orEmpty()))
    }
    var lastEmitted by remember(node.id) { mutableStateOf(node.value) }
    var observed by remember(node.id) { mutableStateOf(node.value) }
    if (node.value != observed) {
        observed = node.value
        if (node.value != lastEmitted) {
            draft = TextFieldValue(node.value?.let(::displayNumber).orEmpty())
            lastEmitted = node.value
        }
    }
    val focus = LocalFocusManager.current
    val error = stringResource(R.string.screen_interactive_ui_invalid_value)
    TextField(
        value = draft,
        onValueChange = {
            draft = it
            val value = numberDraftValue(it.text)
            lastEmitted = (value as? InteractiveUiValue.Number)?.value
            sink(InteractiveUiViewEvent.SetValue(node.id, value))
        },
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = node.label
            if (node.invalid) error(error)
        },
        label = node.label,
        enabled = enabled,
        singleLine = true,
        validity = if (node.invalid) TextFieldValidity.Invalid else TextFieldValidity.None,
        supportingText = error.takeIf { node.invalid },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }, onDone = { focus.clearFocus() }),
    )
}
