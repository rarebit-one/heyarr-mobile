// Root build. Plugin versions are declared here (apply false) and applied in the
// module. Toolchain matches the sibling scaffolds `allthing-android` / `voidbind-kmp`
// (proven-green): AGP 8.7.3, Kotlin 2.3.20, Gradle 8.9.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
}
