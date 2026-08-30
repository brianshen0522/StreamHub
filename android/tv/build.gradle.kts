import java.util.Properties

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

/**
 * Release signing, when a keystore has been configured.
 *
 * The keystore and its passwords live outside the repository and are read from
 * `android/keystore.properties`, which is gitignored — a signing key committed
 * to a public repository is a key anyone can sign with. Without that file the
 * release build is simply unsigned, so a fresh clone still builds.
 *
 * Losing the keystore is not recoverable: Android refuses an update signed
 * with a different key, so every phone and television would have to uninstall
 * first, taking its saved session with it. Back it up.
 */
val streamhubKeystore = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.streamhub.tv"
    compileSdk = 37

    signingConfigs {
        if (streamhubKeystore.containsKey("storeFile")) {
            create("release") {
                storeFile = file(streamhubKeystore.getProperty("storeFile"))
                storePassword = streamhubKeystore.getProperty("storePassword")
                keyAlias = streamhubKeystore.getProperty("keyAlias")
                keyPassword = streamhubKeystore.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.streamhub.tv"
        minSdk = 24
        targetSdk = 37
        versionCode = 13
        versionName = "0.8.3"

        buildConfigField("String", "GIT_SHA", "\"${gitSha()}\"")
        buildConfigField("String", "SERVER_URL", "\"${serverUrl()}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Null when no keystore is configured, which leaves the build
            // unsigned rather than failing.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    implementation(libs.conscrypt.android)
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    // Deliberately no compose material3: tv-material3 carries the same type
    // names, and having both on the classpath makes every import a coin toss
    // between a phone control and a television one.
    implementation(libs.androidx.tv.material)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // zxing arrives through :core, which needs the reader for the phone's
    // scanner; the television uses the writer half to draw its sign-in code.

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource.okhttp)
}
