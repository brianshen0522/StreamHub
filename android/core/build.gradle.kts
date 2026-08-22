plugins {
    // AGP 9 has built-in Kotlin support; applying kotlin-android on top of it
    // is an error, not a redundancy. Compiler plugins are still applied here.
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.streamhub.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        // Android 7 has no java.time. Desugaring provides it rather than the
        // code working around its absence — see minSdk below.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    implementation(libs.androidx.security.crypto)

    // Both halves: the reader for QrDecoder here, and the writer the television
    // draws its code with. One artifact, and it means the decoder can be tested
    // against a code the same library encoded.
    api(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
