import extension.setupDependencyInjection
import extension.testCommonDependencies

/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

plugins {
    id("io.element.android-library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.element.android.libraries.matrix.impl"
}

setupDependencyInjection()

dependencies {
    // Threads Inbox requires the aggregate API on the SDK fork's main branch.
    // Use the same bindings for every variant; mixing published and local APIs
    // makes release compilation differ from the verified debug build.
    require(file("${rootDir.path}/libraries/rustsdk/matrix-rust-sdk.aar").exists()) {
        "Build the matrix-rust-sdk main branch with tools/sdk/build-threads-inbox-sdk before building the app."
    }
    implementation(projects.libraries.rustsdk)
    implementation(projects.libraries.rustlsTls)

    implementation(projects.appconfig)
    implementation(projects.features.enterprise.api)
    implementation(projects.libraries.androidutils)
    implementation(projects.libraries.architecture)
    implementation(projects.libraries.di)
    implementation(projects.libraries.featureflag.api)
    implementation(projects.libraries.network)
    implementation(projects.libraries.preferences.api)
    implementation(projects.libraries.sessionStorage.api)
    implementation(projects.libraries.workmanager.api)
    implementation(projects.services.analytics.api)
    implementation(projects.services.toolbox.api)
    api(projects.libraries.matrix.api)
    implementation(projects.libraries.core)
    implementation(variantOf(libs.jna) { artifactType("aar") })
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.serialization.json)
    implementation(libs.kotlinx.collections.immutable)

    testCommonDependencies(libs)
    testImplementation(projects.features.enterprise.test)
    testImplementation(projects.libraries.featureflag.test)
    testImplementation(projects.libraries.matrix.test)
    testImplementation(projects.libraries.preferences.test)
    testImplementation(projects.libraries.previewutils)
    testImplementation(projects.libraries.sessionStorage.test)
    testImplementation(projects.libraries.workmanager.test)
    testImplementation(projects.services.analytics.test)
    testImplementation(projects.services.toolbox.test)
}
