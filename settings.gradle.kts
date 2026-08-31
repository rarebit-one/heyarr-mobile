rootProject.name = "heyarr-mobile"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

include(":app")

// ── voidbind-kmp consumption (packaging follow-up) ─────────────────────────────
// The real Voidbind login/identity code lives in the sibling repo `voidbind-kmp`
// (the KMP authenticator + shared voidbind-client: WebLoginClient, LoginQr,
// WebLogin, LoginApproval). It does NOT publish a Maven artifact yet
// (0.1.0-SNAPSHOT, no maven-publish plugin), so this app cannot depend on it over
// the wire and CI cannot fetch it.
//
// Until voidbind-kmp extracts + publishes a `voidbind-client` module (plan §5/§6),
// this app ships a thin, wire-compatible `login/` seam that mirrors voidbind-kmp's
// WebLoginClient create/poll + LoginQr tuple byte-for-byte. To wire the real module
// locally once it exists, uncomment the composite build below (expects voidbind-kmp
// checked out as a sibling of this repo):
//
// if (file("../voidbind-kmp/settings.gradle.kts").exists()) {
//     includeBuild("../voidbind-kmp")
// }
