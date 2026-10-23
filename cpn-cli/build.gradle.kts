// Headless command-line front-end.
plugins {
    id("cpn.kmp-cli")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":cpn-api"))
            implementation(libs.clikt)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.io.core)
        }
    }
}
