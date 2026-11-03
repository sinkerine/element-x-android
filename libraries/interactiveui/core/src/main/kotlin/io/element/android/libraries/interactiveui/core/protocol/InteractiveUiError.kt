/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.interactiveui.core.protocol

/** Ported from element-web apps/web/src/interactive-ui/protocol.ts at bcb08017 (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial). */
enum class InteractiveUiErrorCode(val wire: String) {
    NotObject("not_object"),
    BadVersion("bad_version"),
    TooLarge("too_large"),
    MissingField("missing_field"),
    BadType("bad_type"),
    BadId("bad_id"),
    DuplicateId("duplicate_id"),
    UnknownRef("unknown_ref"),
    Cycle("cycle"),
    TooDeep("too_deep"),
    TooMany("too_many"),
    TooLong("too_long"),
    BadValue("bad_value"),
    BadUrl("bad_url"),
    TypeMismatch("type_mismatch");

    companion object {
        fun fromWire(wire: String): InteractiveUiErrorCode? = entries.firstOrNull { it.wire == wire }
    }
}
data class InteractiveUiError(val path: String, val code: InteractiveUiErrorCode)
