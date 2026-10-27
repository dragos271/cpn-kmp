plugins {
    // Loaded once at the root so every module shares the same Kotlin plugin classloader.
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.ktlint) apply false
}

// kotlinWasmNpmInstall does not always write build/wasm/yarn.lock (seen on Windows CI with a warm
// Gradle cache; the Wasm targets have no npm dependencies), and the store task then fails Gradle's
// input validation. There is nothing to store in that case.
tasks.matching { it.name == "kotlinWasmStoreYarnLock" }.configureEach {
    // A local, so the configuration cache does not have to serialize the build script.
    val lock = project.layout.buildDirectory.file("wasm/yarn.lock")
    onlyIf("build/wasm/yarn.lock exists") { lock.get().asFile.exists() }
}
