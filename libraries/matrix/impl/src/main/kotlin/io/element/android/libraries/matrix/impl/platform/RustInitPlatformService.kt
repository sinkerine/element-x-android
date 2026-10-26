/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.platform

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.platform.InitPlatformService
import io.element.android.libraries.matrix.api.tracing.TracingConfiguration
import io.element.android.libraries.matrix.impl.tracing.map
import org.matrix.rustcomponents.sdk.initPlatform
import org.matrix.rustsdk.Android

@ContributesBinding(AppScope::class)
class RustInitPlatformService(
    private val buildMeta: BuildMeta,
    @ApplicationContext private val context: Context,
) : InitPlatformService {
    override fun init(tracingConfiguration: TracingConfiguration) {
        // The verifier and JVM must be ready before the SDK starts its Tokio runtime.
        Android.init(context)
        initPlatform(
            config = tracingConfiguration.map(buildMeta),
            useLightweightTokioRuntime = false
        )
    }
}
