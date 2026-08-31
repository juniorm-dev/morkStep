plugins {
    // AGP 9.0 bundles Kotlin Gradle Plugin 2.2.10 (built-in Kotlin); keep the
    // matching versioned Kotlin plugins explicit. kotlin-android itself is not
    // applied — built-in Kotlin replaces it.
    id("com.android.application") version "9.0.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
    id("com.google.devtools.ksp") version "2.2.10-2.0.2" apply false
}
