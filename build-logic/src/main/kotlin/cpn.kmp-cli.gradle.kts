// Convention for the CLI: a JVM launcher plus native executables. No JS target.
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.DisableCacheInKotlinVersion
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi
import java.net.URI

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
    // On Linux hosts the compiler caches of clikt and clikt-mordant both define
    // `selfAndAncestors`, so debug binaries fail to link with a duplicate symbol. Caches are only
    // used for the host target, which is why cross-compiling from macOS never showed it.
    linuxX64().binaries.all {
        @OptIn(KotlinNativeCacheApi::class)
        disableNativeCache(
            DisableCacheInKotlinVersion.`2_4_21`,
            "clikt and clikt-mordant caches define the same symbol (selfAndAncestors)",
            URI("https://github.com/ajalt/clikt/issues"),
        )
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
