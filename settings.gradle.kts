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
        // ── voidbind-kmp's published `voidbind-client` (GitHub Packages, private) ──
        // The shared Voidbind identity/net/flow brain (WebLoginClient, LoginQr,
        // DeviceIdentity, DeviceKeyStore hardware seam, DevicePairing …) is consumed
        // over the wire from `one.rarebit.voidbind:voidbind-client:<version>`. GitHub
        // Packages requires a token with `read:packages` even for a same-org read:
        //   - CI: `GITHUB_ACTOR` / `GITHUB_TOKEN` (the workflow's own token, with
        //     `packages: read` — see .github/workflows/android.yml).
        //   - Locally: `gpr.user` / `gpr.token` in ~/.gradle/gradle.properties, or the
        //     same env vars, e.g. `GITHUB_ACTOR=<login> GITHUB_TOKEN=$(gh auth token) ./gradlew …`.
        maven {
            name = "GitHubPackagesVoidbindKmp"
            url = uri("https://maven.pkg.github.com/rarebit-one/voidbind-kmp")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.token").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
            // Only voidbind artifacts live here; don't probe it for everything else.
            content { includeGroup("one.rarebit.voidbind") }
        }
    }
}

include(":app")

// ── voidbind-kmp consumption ───────────────────────────────────────────────────
// The app depends on the PUBLISHED `voidbind-client` artifact (see the repository
// above + app/build.gradle.kts). For local development against an unpublished
// voidbind-kmp change, a composite build substitutes the artifact with the sibling
// checkout — uncomment to use (expects voidbind-kmp checked out next to this repo):
//
// if (file("../voidbind-kmp/settings.gradle.kts").exists()) {
//     includeBuild("../voidbind-kmp") {
//         dependencySubstitution {
//             substitute(module("one.rarebit.voidbind:voidbind-client")).using(project(":"))
//         }
//     }
// }
