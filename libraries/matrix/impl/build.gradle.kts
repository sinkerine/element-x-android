import extension.setupDependencyInjection
import extension.testCommonDependencies
import java.io.File
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Properties

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
    // Threads Inbox and log-private media uploads require the verified local SDK.
    // Use the same bindings for every variant; mixing published and local APIs
    // makes release compilation differ from the verified debug build.
    val sdkAar = file("${rootDir.path}/libraries/rustsdk/matrix-rust-sdk.aar")
    val sdkProvenance = file("${rootDir.path}/libraries/rustsdk/matrix-rust-sdk.private.properties")
    val privacyPatch = file("${rootDir.path}/tools/sdk/gallery-log-privacy.patch")
    val sourcePrivacyPatch = file("${rootDir.path}/tools/sdk/gallery-source-log-privacy.patch")
    val rebuildSdkMessage = "Build the log-private SDK with tools/sdk/build-gallery-private-sdk <sdk-repository> before building the app."
    require(sdkAar.exists() && sdkProvenance.exists()) { rebuildSdkMessage }
    fun sha256(input: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(input.inputStream(), digest).use { it.copyTo(OutputStream.nullOutputStream()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    val provenance = Properties().apply { sdkProvenance.inputStream().use { load(it) } }
    require(
        provenance.getProperty("aar.sha256") == sha256(sdkAar) &&
            provenance.getProperty("privacy.patch.sha256") == sha256(privacyPatch) &&
            provenance.getProperty("source.privacy.patch.sha256") == sha256(sourcePrivacyPatch)
    ) { rebuildSdkMessage }
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
