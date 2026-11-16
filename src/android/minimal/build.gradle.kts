// [T-minimal] The whole app.
//
// This module exists because the previous approach — removing features from a
// 165 000-line app one reference at a time — is the wrong shape of work. Every
// module in that app is referenced from somewhere, so every removal is a
// surgery with a build cycle attached, and the result is still a monolith with
// holes in it.
//
// Here the direction is inverted: the app starts empty and gains only what it
// needs. That is why adding something back costs one file rather than an
// archaeology expedition into code nobody remembers writing.
//
// What it contains, deliberately:
//   * a chat box that streams from DeepSeek,
//   * the conversations that box produces,
//   * one screen of settings (the API key).
//
// What it does not contain, also deliberately: tools, a shell, a sandbox, voice,
// browsers, MCP, subagents, providers other than DeepSeek, backup, scheduled
// tasks, twenty-two languages. Each of those is a decision to make later, with
// evidence, rather than a default inherited from someone else's product.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.openminis.minimal"

    // The same signing machinery as the full app: a CI build must share one
    // identity across runs, or build N+1 cannot install over build N.
    signingConfigs {
        val explicitStore = System.getenv("MINIS_SIGNING_STORE_FILE")
        if (!explicitStore.isNullOrBlank()) {
            create("ciFixed") {
                storeFile = file(explicitStore)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    compileSdk = 36

    defaultConfig {
        // Its own package: this installs beside the full app so the two can be
        // compared on one device without either wiping the other's data.
        applicationId = "com.openminis.minimal"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            // No minification. R8 on a 3 000-line app saves a few hundred KB
            // and costs two minutes of every build plus a class of bugs that only
            // appears in release. Revisit when the size actually matters.
            isMinifyEnabled = false
            signingConfig =
                if (!System.getenv("MINIS_SIGNING_STORE_FILE").isNullOrBlank())
                    signingConfigs.getByName("ciFixed")
                else
                    signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // SSE from the DeepSeek endpoint. okhttp-sse exists, but parsing one event
    // stream by hand is thirty lines and removes a dependency whose retry
    // semantics would need learning anyway.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // The one piece of UI worth taking from the full app unchanged: model output
    // is markdown, and reading it as plain text is a materially worse
    // experience. Same artifact, same version — it is already known to resolve
    // and to render on this Compose version.
    implementation("com.mikepenz:multiplatform-markdown-renderer-android:0.33.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3-android:0.33.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
