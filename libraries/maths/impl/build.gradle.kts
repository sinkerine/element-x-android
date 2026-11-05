/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

import extension.setupDependencyInjection
import extension.testCommonDependencies

plugins {
    id("io.element.android-library")
}

android {
    namespace = "io.element.android.libraries.maths.impl"
    testOptions.unitTests.isIncludeAndroidResources = true
}

setupDependencyInjection()

dependencies {
    api(projects.libraries.maths.api)
    implementation(projects.libraries.di)
    implementation(libs.ratex.android)
    implementation(libs.androidx.corektx)
    testCommonDependencies(libs)
}
