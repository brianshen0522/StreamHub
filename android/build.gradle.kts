plugins {
    // No kotlin-android here: AGP 9 brings Kotlin support itself, and applying
    // that plugin as well fails the build.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
