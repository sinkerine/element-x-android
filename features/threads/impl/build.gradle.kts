import extension.setupDependencyInjection
import extension.testCommonDependencies

plugins { id("io.element.android-compose-library") }
android {
    namespace = "io.element.android.features.threads.impl"
    testOptions.unitTests.isIncludeAndroidResources = true
}
setupDependencyInjection()
dependencies {
    api(projects.features.threads.api)
    implementation(libs.coroutines.core)
    implementation(projects.libraries.di)
    implementation(projects.libraries.eventformatter.api)
    testImplementation(projects.libraries.eventformatter.test)
    implementation(projects.libraries.designsystem)
    implementation(projects.libraries.uiStrings)
    testCommonDependencies(libs, true)
    testImplementation(projects.libraries.matrix.test)
    testImplementation(projects.features.threads.test)
}
