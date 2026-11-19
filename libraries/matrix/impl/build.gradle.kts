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

// `-PelementCallLocalVersion` points this at a locally published build of element-call-android.
// Goes with the mavenLocal block in settings.gradle.kts. Only the BOM carries a version; every other artifact follows it.
val elementCallVersion: String = providers.gradleProperty("elementCallLocalVersion")
    .getOrElse(libs.versions.element.call.get())

dependencies {
    // Threads Inbox and log-private media uploads require the verified local SDK.
    // Use the same bindings for every variant; mixing published and local APIs
    // makes release compilation differ from the verified debug build.
    val sdkAar = file("${rootDir.path}/libraries/rustsdk/matrix-rust-sdk.aar")
    val sdkProvenance = file("${rootDir.path}/libraries/rustsdk/matrix-rust-sdk.private.properties")
    val privacyPatch = file("${rootDir.path}/tools/sdk/gallery-log-privacy.patch")
    val sourcePrivacyPatch = file("${rootDir.path}/tools/sdk/gallery-source-log-privacy.patch")
    val sdkRevision = "b303bb5785980bd860310bb2f5f8b301fa351172"
    val sdkChecksum = "02f1dcefb2cfff3a543e86870707283b7166358bb5ff1912088d17e1ffbbdd3d"
    val rebuildSdkMessage = "Install the verified local Matrix SDK release AAR and its provenance. See tools/sdk/threads-inbox-sdk.md."
    require(sdkAar.exists() && sdkProvenance.exists()) { rebuildSdkMessage }
    fun sha256(input: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(input.inputStream(), digest).use { it.copyTo(OutputStream.nullOutputStream()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    val provenance = Properties().apply { sdkProvenance.inputStream().use { load(it) } }
    require(
        provenance.getProperty("sdk.revision") == sdkRevision &&
            provenance.getProperty("aar.sha256") == sdkChecksum &&
            sha256(sdkAar) == sdkChecksum &&
            provenance.getProperty("privacy.patch.sha256") == sha256(privacyPatch) &&
            provenance.getProperty("source.privacy.patch.sha256") == sha256(sourcePrivacyPatch)
    ) { rebuildSdkMessage }
    implementation(projects.libraries.rustsdk)

    // The native call's MatrixBackend, over the SDK client. Only this module may hand it a raw SDK client.
    // element-call carries the RTC core, where MatrixBackend lives, as an api dependency.
    implementation(platform("io.element.android:element-call-bom:$elementCallVersion"))
    implementation(libs.element.call.impl)
    implementation(projects.features.callnative.api)

    implementation(projects.appconfig)
    implementation(projects.features.enterprise.api)
    implementation(projects.features.networkmonitor.api)
    implementation(projects.libraries.androidutils)
    implementation(projects.libraries.architecture)
    implementation(projects.libraries.di)
    implementation(projects.libraries.featureflag.api)
    implementation(projects.libraries.network)
    implementation(projects.libraries.sessionStorage.api)
    implementation(projects.libraries.workmanager.api)
    implementation(projects.services.analytics.api)
    implementation(projects.services.toolbox.api)
    api(projects.libraries.matrix.api)
    implementation(projects.libraries.core)
    implementation(variantOf(libs.jna) { artifactType("aar") })
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.serialization.json)
    implementation(libs.androidx.lifecycle.process)

    testCommonDependencies(libs)
    testImplementation(projects.features.enterprise.test)
    testImplementation(projects.features.networkmonitor.test)
    testImplementation(projects.libraries.featureflag.test)
    testImplementation(projects.libraries.matrix.test)
    testImplementation(projects.libraries.previewutils)
    testImplementation(projects.libraries.sessionStorage.test)
    testImplementation(projects.libraries.workmanager.test)
    testImplementation(projects.services.analytics.test)
    testImplementation(projects.services.toolbox.test)
}
