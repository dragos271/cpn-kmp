plugins {
    // Loaded once at the root so every module shares the same Kotlin plugin classloader.
    alias(libs.plugins.kotlin.multiplatform) apply false
}
