// Convention for engine modules: pure commonMain code compiled for every supported target.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jlleitschuh.gradle.ktlint")
}

// Precompiled script plugins cannot use the type-safe `libs` accessor.
val libs = the<VersionCatalogsExtension>().named("libs")

kotlin {
    explicitApi()

    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    js(IR) {
        nodejs()
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
    }
    macosArm64()
    macosX64()
    linuxX64()
    mingwX64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.findLibrary("kotest-property").get())
            implementation(libs.findLibrary("kotlinx-coroutines-test").get())
        }
    }
}
