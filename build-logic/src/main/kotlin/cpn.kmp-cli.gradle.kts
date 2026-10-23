// Convention for the CLI: a JVM launcher plus native executables. No JS target.
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jlleitschuh.gradle.ktlint")
}

kotlin {
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        binaries {
            executable {
                mainClass.set("no.hvl.cpn.cli.MainKt")
                applicationName.set("cpn")
            }
        }
    }
    listOf(macosArm64(), macosX64(), linuxX64(), mingwX64()).forEach { target ->
        target.binaries.executable {
            entryPoint = "no.hvl.cpn.cli.main"
            baseName = "cpn"
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
