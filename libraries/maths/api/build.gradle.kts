/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

import extension.testCommonDependencies

plugins {
    id("io.element.android-library")
}

android {
    namespace = "io.element.android.libraries.maths.api"
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    api(libs.jsoup)
    implementation(libs.androidx.annotationjvm)
    implementation(libs.androidx.corektx)
    testCommonDependencies(libs)
    testImplementation(projects.libraries.maths.test)
}
