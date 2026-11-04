/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.core.session

import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.interactiveui.core.action.InteractiveUiActionContentBuilder
import io.element.android.libraries.interactiveui.core.action.InteractiveUiEventContext
import io.element.android.libraries.interactiveui.core.action.InteractiveUiNonce
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiAction
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiConstants
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiNode
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiPayload
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiStatus
import io.element.android.libraries.interactiveui.core.protocol.InteractiveUiValue
import io.element.android.libraries.interactiveui.core.protocol.toJsonElement
import io.element.android.libraries.interactiveui.core.state.InteractiveUiStateRules
import io.element.android.libraries.interactiveui.core.state.InteractiveUiSubmission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import timber.log.Timber

/**
 * Ported from element-web apps/web/src/viewmodels/message-body/InteractiveUiBodyViewModel.ts at bcb08017
 * (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial).
 */
data class InteractiveUiSessionState(
    val payload: InteractiveUiPayload,
    val values: Map<String, InteractiveUiValue>,
    val numberDrafts: Map<String, Double?>,
    val invalidKeys: Set<String>,
    val collapsibleOpen: Map<String, Boolean>,
    val graphSelection: Map<String, String?>,
    val graphListMode: Set<String>,
    val chartDataMode: Set<String>,
    val tableSort: Map<String, InteractiveUiTableSort>,
    val carouselIndex: Map<String, Int>,
    val phase: InteractiveUiPhase,
    val notice: InteractiveUiNotice?,
    val confirmationOverdue: Boolean,
    val showAsText: Boolean,
    val urlRequest: InteractiveUiUrlRequest?,
    val pendingNodeId: String?,
)

/**
 * Ported from element-web apps/web/src/viewmodels/message-body/InteractiveUiBodyViewModel.ts at bcb08017
 * (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial).
 * Mutations and asynchronous completions share this session's monitor; no monitor is held across suspension.
 */
class InteractiveUiSession(
    private val context: InteractiveUiEventContext,
    initialPayload: InteractiveUiPayload,
    savedState: JsonElement?,
    private val scope: CoroutineScope,
    private val sendAction: suspend (content: JsonObject) -> Result<Unit>,
    private val persistState: suspend (envelope: JsonObject) -> Unit,
    private val nonce: () -> String = { InteractiveUiNonce.generate() },
) {
    private val mutableState = MutableStateFlow(
        initialState(initialPayload, InteractiveUiStateRules.mergeSavedState(initialPayload, savedState))
            .copy(carouselIndex = restoredCarouselIndices(initialPayload, savedState))
    )
    val state: StateFlow<InteractiveUiSessionState> = mutableState.asStateFlow()
    private val current get() = mutableState.value
    private var disposed = false
    private var pristine = true
    private var sendInFlight = false
    private var persistJob: Job? = null
    private var confirmationJob: Job? = null
    private val persistMutex = Mutex()
    private val active get() = !disposed && !sendInFlight && current.phase == InteractiveUiPhase.Active

    @Synchronized
    fun onRevision(payload: InteractiveUiPayload) {
        if (disposed || payload.revision <= current.payload.revision) return
        val values = InteractiveUiStateRules.mergeSavedState(payload, savedStateEnvelope())
        confirmationJob?.cancel()
        val phase = if (sendInFlight && payload.status == InteractiveUiStatus.Active) InteractiveUiPhase.Sending else phase(payload)
        changed(initialState(payload, values).copy(phase = phase, showAsText = current.showAsText, pendingNodeId = current.pendingNodeId), userChange = false)
    }

    @Synchronized
    fun applySavedState(savedState: JsonElement) {
        if (disposed || !pristine) return
        changed(current.copy(
            values = InteractiveUiStateRules.mergeSavedState(current.payload, savedState),
            carouselIndex = restoredCarouselIndices(current.payload, savedState)
        ), userChange = false)
    }

    @Synchronized
    fun setValue(nodeId: String, value: InteractiveUiValue) {
        if (!active) return
        val node = node(nodeId) ?: return
        val key = InteractiveUiStateRules.binding(node)
        if (node is InteractiveUiNode.NumberInput && !node.slider) {
            if (value != InteractiveUiValue.Null && (value !is InteractiveUiValue.Number || !value.value.isFinite())) return
            if (value == InteractiveUiValue.Null || !InteractiveUiStateRules.acceptsStateValue(current.payload, node.bind, value)) {
                changed(current.copy(numberDrafts = current.numberDrafts + (node.bind to (value as? InteractiveUiValue.Number)?.value)))
                return
            }
        }
        if (key != null) {
            updateValue(key, value)
        } else if (node is InteractiveUiNode.Collapsible && value is InteractiveUiValue.Bool) {
            changed(current.copy(collapsibleOpen = current.collapsibleOpen + (nodeId to value.value)))
        }
    }

    @Synchronized
    fun selectGraphNode(nodeId: String, graphNodeId: String?) {
        if (!active) return
        val node = node(nodeId) as? InteractiveUiNode.Graph ?: return
        if (graphNodeId != null && node.nodes.none { it.id == graphNodeId }) return
        if (node.bindSelected != null) {
            updateValue(node.bindSelected, graphNodeId?.let(InteractiveUiValue::Text) ?: InteractiveUiValue.Null)
        } else {
            changed(current.copy(graphSelection = current.graphSelection + (nodeId to graphNodeId)))
        }
    }

    @Synchronized
    fun setGraphListMode(nodeId: String, asList: Boolean) {
        if (!active || node(nodeId) !is InteractiveUiNode.Graph) return
        changed(current.copy(graphListMode = if (asList) current.graphListMode + nodeId else current.graphListMode - nodeId))
    }

    @Synchronized
    fun setChartDataMode(nodeId: String, showData: Boolean) {
        if (!active || node(nodeId) !is InteractiveUiNode.Chart) return
        changed(current.copy(chartDataMode = if (showData) current.chartDataMode + nodeId else current.chartDataMode - nodeId))
    }

    @Synchronized
    fun sortTable(nodeId: String, columnKey: String) {
        if (!active) return
        val node = node(nodeId) as? InteractiveUiNode.Table ?: return
        if (!node.sortable || node.columns.none { it.key == columnKey }) return
        val old = current.tableSort[nodeId]
        changed(current.copy(tableSort = current.tableSort + (nodeId to InteractiveUiTableSort(columnKey, old?.columnKey == columnKey && !old.descending))))
    }

    @Synchronized
    fun setShowAsText(showAsText: Boolean) {
        if (!disposed) changed(current.copy(showAsText = showAsText))
    }

    @Synchronized
    fun setCarouselIndex(nodeId: String, index: Int) {
        if (!active) return
        val node = node(nodeId) as? InteractiveUiNode.Carousel ?: return
        if (index !in node.items.indices) return
        changed(current.copy(carouselIndex = current.carouselIndex + (nodeId to index)))
    }

    @Synchronized
    fun activate(nodeId: String) {
        if (!active) return
        val node = node(nodeId) as? InteractiveUiNode.Button ?: return
        perform(node.action, nodeId, node.label)
    }

    @Synchronized
    fun activateCarouselItem(nodeId: String, itemId: String) {
        if (!active) return
        val node = node(nodeId) as? InteractiveUiNode.Carousel ?: return
        val item = node.items.firstOrNull { it.id == itemId } ?: return
        item.action?.let { perform(it, nodeId, item.title, itemId) }
    }

    @Synchronized
    fun submitForm(nodeId: String) {
        if (!active) return
        val node = node(nodeId) as? InteractiveUiNode.Form ?: return
        send(nodeId, node.submitLabel, node.singleUse)
    }

    @Synchronized
    fun openLink(nodeId: String) {
        if (!active) return
        val node = node(nodeId) as? InteractiveUiNode.Link ?: return
        changed(current.copy(urlRequest = InteractiveUiUrlRequest.Confirming(node.url)))
    }

    @Synchronized
    fun confirmOpenUrl() {
        if (!active) return
        val request = current.urlRequest as? InteractiveUiUrlRequest.Confirming ?: return
        changed(current.copy(urlRequest = InteractiveUiUrlRequest.Approved(request.url)))
    }

    @Synchronized
    fun clearUrlRequest() {
        if (!disposed) changed(current.copy(urlRequest = null))
    }

    @Synchronized
    fun retryAfterOverdue() {
        if (disposed || !current.confirmationOverdue || current.payload.status != InteractiveUiStatus.Active) return
        confirmationJob?.cancel()
        changed(current.copy(phase = InteractiveUiPhase.Active, notice = notice(current.payload), confirmationOverdue = false))
    }

    @Synchronized
    fun dispose() {
        if (disposed) return
        disposed = true
        confirmationJob?.cancel()
        persistJob?.cancel()
        val saved = savedStateEnvelope()
        scope.launch { persist(saved) }
    }

    private fun perform(action: InteractiveUiAction, nodeId: String, label: String, itemId: String? = null) {
        when (action) {
            is InteractiveUiAction.SetState -> updateValue(action.key, action.value)
            is InteractiveUiAction.ToggleState -> {
                val old = current.values[action.key] as? InteractiveUiValue.Bool ?: return
                updateValue(action.key, InteractiveUiValue.Bool(!old.value))
            }
            is InteractiveUiAction.OpenUrl -> changed(current.copy(urlRequest = InteractiveUiUrlRequest.Confirming(action.url)))
            is InteractiveUiAction.Submit -> send(nodeId, label, action.singleUse, itemId)
        }
    }

    private fun updateValue(key: String, value: InteractiveUiValue) {
        if (!InteractiveUiStateRules.acceptsStateValue(current.payload, key, value)) return
        val copy = if (value is InteractiveUiValue.TextList) value.copy(values = value.values.toList()) else value
        changed(current.copy(values = current.values + (key to copy), numberDrafts = current.numberDrafts - key, invalidKeys = current.invalidKeys - key))
    }

    private fun send(nodeId: String, label: String, singleUse: Boolean, itemId: String? = null) {
        val payload = current.payload
        val drafts = current.numberDrafts.mapValues { (_, value) -> value?.let(InteractiveUiValue::Number) ?: InteractiveUiValue.Null }
        val submission = InteractiveUiStateRules.validateSubmission(payload, nodeId, current.values + drafts, itemId)
        val invalid = when (submission) {
            is InteractiveUiSubmission.Invalid -> submission.invalidKeys
            is InteractiveUiSubmission.Valid -> submission.values.keys.intersect(drafts.keys)
        }
        if (submission !is InteractiveUiSubmission.Valid || invalid.isNotEmpty()) {
            changed(current.copy(invalidKeys = invalid))
            return
        }
        sendInFlight = true
        changed(current.copy(
            phase = InteractiveUiPhase.Sending,
            notice = null,
            invalidKeys = emptySet(),
            confirmationOverdue = false,
            pendingNodeId = nodeId,
            urlRequest = null
        ))
        var result: Result<Unit>? = null
        scope.launch {
            result = runCatchingExceptions {
                val content = InteractiveUiActionContentBuilder.build(context, payload, submission.actionId, submission.values, label, nonce())
                sendAction(content).getOrThrow()
            }
        }.invokeOnCompletion {
            synchronized(this@InteractiveUiSession) {
                sendInFlight = false
                if (!disposed) finishSend(payload, singleUse, result)
            }
        }
    }

    private fun finishSend(payload: InteractiveUiPayload, singleUse: Boolean, result: Result<Unit>?) {
        val unchanged = current.payload === payload
        val awaiting = result?.isSuccess == true && unchanged && singleUse
        val failed = result?.isFailure == true
        if (failed) Timber.w("interactive_ui send failed: %s", result.exceptionOrNull()?.javaClass?.simpleName)
        changed(current.copy(
            phase = if (awaiting) InteractiveUiPhase.AwaitingConfirmation else phase(current.payload),
            notice = if (failed && unchanged) InteractiveUiNotice.SendFailed else notice(current.payload),
            pendingNodeId = null
        ), userChange = false)
        if (awaiting) {
            confirmationJob = scope.launch {
                delay(InteractiveUiConstants.CONFIRMATION_OVERDUE_MILLIS)
                synchronized(this@InteractiveUiSession) {
                    if (!disposed && current.payload === payload && current.phase == InteractiveUiPhase.AwaitingConfirmation) {
                        changed(current.copy(confirmationOverdue = true, notice = InteractiveUiNotice.WaitingForSender), userChange = false)
                    }
                }
            }
        }
    }

    private fun node(id: String): InteractiveUiNode? {
        val pending = mutableListOf(current.payload.root)
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val next = pending.removeAt(pending.lastIndex)
            val node = current.payload.nodesById[next]
            val visible = node?.visibleWhen?.let { InteractiveUiStateRules.evaluateCondition(it, current.values) } ?: true
            if (visited.add(next) && node != null && visible) {
                if (next == id) return node
                pending.addAll(InteractiveUiStateRules.childIds(node))
            }
        }
        return null
    }

    private fun changed(value: InteractiveUiSessionState, userChange: Boolean = true) {
        if (userChange) pristine = false
        mutableState.value = value
        persistJob?.cancel()
        val saved = savedStateEnvelope()
        persistJob = scope.launch {
            delay(InteractiveUiConstants.PERSIST_DEBOUNCE_MILLIS)
            persist(saved)
        }
    }

    /**
     * Device-local extension of the Web bound-state envelope. Only settled carousel item IDs are retained;
     * pending actions, URL requests and other transient view state never cross session recreation.
     */
    @Synchronized
    fun savedStateEnvelope(): JsonObject = buildJsonObject {
        put("ui_id", current.payload.uiId)
        put("revision", current.payload.revision)
        put("state", JsonObject(current.values.mapValues { it.value.toJsonElement() }))
        val selectedItems = buildJsonObject {
            current.payload.nodes.filterIsInstance<InteractiveUiNode.Carousel>().forEach { node ->
                current.carouselIndex[node.id]?.let { index -> node.items.getOrNull(index)?.let { put(node.id, it.id) } }
            }
        }
        if (selectedItems.isNotEmpty()) put("android_view", buildJsonObject { put("carousel_selected_items", selectedItems) })
    }

    private fun restoredCarouselIndices(payload: InteractiveUiPayload, saved: JsonElement?): Map<String, Int> {
        val envelope = saved as? JsonObject ?: return emptyMap()
        if (envelope["state"] !is JsonObject) return emptyMap()
        if ((envelope["ui_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content != payload.uiId) return emptyMap()
        // Revisions intentionally reset view state. Never restore an old item selection onto a new payload.
        val revision = (envelope["revision"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        if (revision != payload.revision) return emptyMap()
        val view = envelope["android_view"] as? JsonObject ?: return emptyMap()
        val selectedItems = view["carousel_selected_items"] as? JsonObject ?: return emptyMap()
        return payload.nodes.filterIsInstance<InteractiveUiNode.Carousel>().mapNotNull { node ->
            val itemId = (selectedItems[node.id] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val index = node.items.indexOfFirst { it.id == itemId }
            if (index < 0) null else node.id to index
        }.toMap()
    }

    private suspend fun persist(saved: JsonObject) {
        persistMutex.withLock {
            runCatchingExceptions { persistState(saved) }
                .onFailure { Timber.w("interactive_ui persistence failed: %s", it.javaClass.simpleName) }
        }
    }

    private fun initialState(payload: InteractiveUiPayload, values: Map<String, InteractiveUiValue>) = InteractiveUiSessionState(
        payload,
        values,
        emptyMap(),
        emptySet(),
        emptyMap(),
        emptyMap(),
        emptySet(),
        emptySet(),
        emptyMap(),
        emptyMap(),
        phase(payload),
        notice(payload),
        false,
        false,
        null,
        null,
    )

    private fun phase(payload: InteractiveUiPayload): InteractiveUiPhase = when (payload.status) {
        InteractiveUiStatus.Active -> InteractiveUiPhase.Active
        InteractiveUiStatus.Submitted -> InteractiveUiPhase.Submitted
        InteractiveUiStatus.Expired -> InteractiveUiPhase.Expired
        InteractiveUiStatus.Superseded -> InteractiveUiPhase.Superseded
    }

    private fun notice(payload: InteractiveUiPayload): InteractiveUiNotice? = payload.statusText?.let(InteractiveUiNotice::PayloadText)
}
