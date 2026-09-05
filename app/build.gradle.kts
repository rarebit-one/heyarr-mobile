import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ── Release version + signing ────────────────────────────────────────────────
// `versionName` comes from the git tag: CI passes `-PreleaseVersionName=$GITHUB_REF_NAME`
// (the tag, e.g. `v0.2.1`); a plain local build falls back to the constant below.
// `versionCode` is DERIVED from it (major*10000 + minor*100 + patch) so it is
// monotonic with the tag and never has to be hand-bumped.
val releaseVersionName: String =
    providers.gradleProperty("releaseVersionName").orNull
        ?.trim()?.removePrefix("v")?.takeIf { it.isNotEmpty() }
        ?: "0.3.1"

fun versionCodeOf(name: String): Int {
    val parts = name.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    return parts.getOrElse(0) { 0 } * 10_000 + parts.getOrElse(1) { 0 } * 100 + parts.getOrElse(2) { 0 }
}

// Signing material is read from the environment (CI: repo secrets) or gradle
// properties (locally: ~/.gradle/gradle.properties). NOTHING is ever committed —
// `*.jks` is git-ignored and the keystore is materialised into build/ from base64.
// The keys live at ~/.config/rarebit-android-signing/ and in 1Password (Sysadmins).
fun releaseSecret(env: String, property: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: providers.gradleProperty(property).orNull?.takeIf { it.isNotBlank() }

val releaseKeystoreBase64 = releaseSecret("RELEASE_KEYSTORE_BASE64", "release.keystoreBase64")
val releaseKeystorePassword = releaseSecret("RELEASE_KEYSTORE_PASSWORD", "release.keystorePassword")
val releaseKeyAlias = releaseSecret("RELEASE_KEY_ALIAS", "release.keyAlias")
val releaseKeyPassword = releaseSecret("RELEASE_KEY_PASSWORD", "release.keyPassword")

// Present only when the base64 keystore was supplied; otherwise the release build
// type stays unsigned (a local `assembleRelease` still works, it just isn't signed).
val releaseKeystore: File? = releaseKeystoreBase64?.let { encoded ->
    layout.buildDirectory.file("release-signing/release.jks").get().asFile.apply {
        parentFile.mkdirs()
        writeBytes(Base64.getMimeDecoder().decode(encoded))
    }
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
        versionCode = versionCodeOf(releaseVersionName)
        versionName = releaseVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Where the app points by default: the live Bartley Ridge heyarr node, now
        // served over internal TLS (valid Let's Encrypt cert at
        // https://heyarr.br.thesim.family:7777, reachable on the LAN and via the
        // Tailscale subnet route when out). Override at build time with
        // `-PheyarrBaseUrl=…` (or in gradle.properties); override at runtime from the
        // in-app Settings screen (persisted in SharedPreferences) — e.g. back to the
        // plain-http node IP as a fallback while TLS beds in.
        val heyarrBaseUrl = (project.findProperty("heyarrBaseUrl") as String?)
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?: "https://heyarr.br.thesim.family:7777"
        buildConfigField("String", "HEYARR_BASE_URL", "\"$heyarrBaseUrl\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("release") {
            // Left unconfigured (and unreferenced) when no keystore was supplied.
            releaseKeystore?.let { keystore ->
                storeFile = keystore
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // minify stays OFF until proguard rules exist for the reflective bits.
            isMinifyEnabled = false
            signingConfig = releaseKeystore?.let { signingConfigs.getByName("release") }
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

    // ── Navigation ───────────────────────────────────────────────────────────────
    // Typed routes (nav/Routes.kt). kotlinx-serialization is here for ROUTE ARGUMENTS
    // ONLY — wire JSON stays hand-read on net/JsonScan, the repo's convention.
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")
    // Test-only: the route round-trip test encodes through JSON; the app never does.
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // HTTP for the login seam, library browse, blob-stream and personal-state fetches.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Posters. Rides the SAME OkHttp client (AppGraph) so a poster fetch carries the
    // credential through net/AuthInterceptor without ever seeing it.
    implementation("io.coil-kt:coil-compose:2.7.0")

    // QR encoding for the `voidbind:login?…` tuple (pure Java — the BitMatrix half is
    // JVM-unit-tested; only the Bitmap conversion touches Android).
    implementation("com.google.zxing:core:3.5.3")

    // ── Voidbind shared client (the identity seam) ──────────────────────────────
    // `WebLoginClient`/`LoginQr` (QR web-login, wire-identical to voidbind-go),
    // `DeviceIdentity` + the hardware-sealed `DeviceKeyStore` (ADR-0001), `Cert`,
    // and the `DevicePairing` relay flow that enrols this device. Resolved from
    // GitHub Packages (settings.gradle.kts).
    implementation("one.rarebit.voidbind:voidbind-client:0.6.0")
    // The device key's hardware wrapping key is user-auth-gated: BiometricPrompt
    // needs a FragmentActivity, and a modern fragment so it still extends the
    // ComponentActivity that activity-compose's setContent requires.
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment:1.8.3")

    // ── Invite QR scanning (Enrol → scan the Mac's `voidbind:pair?…` QR) ───────
    // CameraX preview + analysis with ML Kit barcode decoding — the same stack and
    // versions as the Voidbind authenticator (voidbind-kmp androidApp), so both apps
    // scan alike on the same phone. Validation of what was scanned is the library's
    // `Invite.decode` (device/PairInvite), never a re-derived parser.
    val cameraX = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // ── Media3 / ExoPlayer (M10 playback) ───────────────────────────────────────
    // The player itself, the PlayerView (transport controls) and the OkHttp-backed
    // HTTP data source. ExoPlayer's HTTP data source issues Range requests and
    // handles 206 partial content natively; we point it at the authenticated blob
    // endpoint and inject the Authorization header as a default request property.
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    // The audio queue lives in a MediaSessionService (notification controls, survives the
    // Activity); the app talks to it through a MediaController behind the AudioPlayer seam.
    implementation("androidx.media3:media3-session:$media3")

    // ── The reader (Readium Kotlin toolkit) ─────────────────────────────────────
    // EPUB, PDF (pdfium) and comic archives, fetched over the authenticated blob route
    // through a DefaultHttpClient callback (reader/ReaderHttp). Readium needs core
    // library desugaring (compileOptions below).
    val readium = "3.1.1"
    implementation("org.readium.kotlin-toolkit:readium-shared:$readium")
    implementation("org.readium.kotlin-toolkit:readium-streamer:$readium")
    implementation("org.readium.kotlin-toolkit:readium-navigator:$readium")
    implementation("org.readium.kotlin-toolkit:readium-adapter-pdfium:$readium")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    // ── Unit tests (pure JVM — no Android runtime) ──────────────────────────────
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
