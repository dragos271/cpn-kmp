// Kotlin Multiplatform app: one Compose UI for desktop (JVM) and the browser (Wasm), on top of the headless engine.
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    id("org.jlleitschuh.gradle.ktlint")
}

kotlin {
    jvm("desktop") {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("cpn-app")
        browser {
            commonWebpackConfig {
                outputFileName = "cpn-app.js"
            }
            testTask { enabled = false }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":cpn-api"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

// The example models in /examples are bundled as app resources, so the app and the CLI run the same files.
val syncExampleModels by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory.dir("examples"))
    into(layout.buildDirectory.dir("exampleResources/files"))
}

compose.resources {
    packageOfResClass = "no.hvl.cpn.app.resources"
    customDirectory(
        sourceSetName = "commonMain",
        directoryProvider = syncExampleModels.map { layout.buildDirectory.dir("exampleResources").get() },
    )
}

compose.desktop {
    application {
        mainClass = "no.hvl.cpn.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "CPN Tool"
            packageVersion = "1.0.0"
        }
    }
}
