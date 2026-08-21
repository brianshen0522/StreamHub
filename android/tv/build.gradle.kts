plugins {
    // AGP 9 has built-in Kotlin support; applying kotlin-android on top of it
    // is an error, not a redundancy.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Nothing pushes updates to a sideloaded build, so "which commit is on this
 * device" is a real question with no other way to answer it. A television is the
 * device least likely to be reachable for a manual check, so the settings screen
 * shows this.
 */
fun gitSha(): String = runCatching {
    val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    process.inputStream.bufferedReader().readText().trim().ifEmpty { "unknown" }
}.getOrDefault("unknown")

/**
 * The server this build talks to. There is one deployment, so asking for its
 * address would be a setup step with exactly one correct answer — and typing a
 * URL on a remote control is the worst version of that step on any platform.
 *
 * Override for local work — a debug build may use http, a release build may not:
 *   ./gradlew :tv:installDebug -Pstreamhub.serverUrl=http://10.0.2.2:58787
 */
fun serverUrl(): String =
    (findProperty("streamhub.serverUrl") as String?)?.trimEnd('/')
        ?: "https://streamhub.gugulu.tw"

android {
    namespace = "com.streamhub.tv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.streamhub.tv"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "GIT_SHA", "\"${gitSha()}\"")
        buildConfigField("String", "SERVER_URL", "\"${serverUrl()}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.tv.material)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
}
