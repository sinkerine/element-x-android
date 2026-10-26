plugins { id("io.element.android-compose-library") }
android { namespace = "io.element.android.features.threads.test" }

dependencies {
    api(projects.features.threads.api)
    implementation(libs.coroutines.core)
}
