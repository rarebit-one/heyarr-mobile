plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "one.rarebit.heyarr.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "one.rarebit.heyarr.mobile"
        // minSdk 33: the published `voidbind-client` Android variant is minSdk 33
        // (StrongBox + a platform Ed25519 provider + the modern BiometricPrompt API).
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Where the app points by default: the live Bartley Ridge heyarr node (plain
        // HTTP on the LAN, reachable via the Tailscale subnet route when out). Override
        // at build time with `-PheyarrBaseUrl=…` (or in gradle.properties); override at
        // runtime from the in-app Settings screen (persisted in SharedPreferences).
        val heyarrBaseUrl = (project.findProperty("heyarrBaseUrl") as String?)
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?: "http://192.168.16.224:7777"
        buildConfigField("String", "HEYARR_BASE_URL", "\"$heyarrBaseUrl\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // ── Compose UI ───────────────────────────────────────────────────────────────
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // HTTP for the login seam, library browse, blob-stream and personal-state fetches.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // QR encoding for the `voidbind:login?…` tuple (pure Java — the BitMatrix half is
    // JVM-unit-tested; only the Bitmap conversion touches Android).
    implementation("com.google.zxing:core:3.5.3")

    // ── Voidbind shared client (the identity seam) ──────────────────────────────
    // `WebLoginClient`/`LoginQr` (QR web-login, wire-identical to voidbind-go),
    // `DeviceIdentity` + the hardware-sealed `DeviceKeyStore` (ADR-0001), `Cert`,
    // and the `DevicePairing` relay flow that enrols this device. Resolved from
    // GitHub Packages (settings.gradle.kts).
    implementation("one.rarebit.voidbind:voidbind-client:0.1.0")
    // The device key's hardware wrapping key is user-auth-gated: BiometricPrompt
    // needs a FragmentActivity, and a modern fragment so it still extends the
    // ComponentActivity that activity-compose's setContent requires.
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment:1.8.3")

    // ── Media3 / ExoPlayer (M10 playback) ───────────────────────────────────────
    // The player itself, the PlayerView (transport controls) and the OkHttp-backed
    // HTTP data source. ExoPlayer's HTTP data source issues Range requests and
    // handles 206 partial content natively; we point it at the authenticated blob
    // endpoint and inject the Authorization header as a default request property.
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")

    // ── Unit tests (pure JVM — no Android runtime) ──────────────────────────────
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
