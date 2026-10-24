plugins { id("io.element.android-compose-library") }
android { namespace = "io.element.android.features.threads.api" }

dependencies {
    api(projects.libraries.matrix.api)
    api(projects.libraries.architecture)
    implementation(libs.coroutines.core)
}
