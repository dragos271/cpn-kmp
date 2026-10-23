// .cpn importer, JSON model format and report writers.
plugins {
    id("cpn.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":cpn-core"))
            api(project(":cpn-lang"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            // Loaded models are checked by simulating them.
            implementation(project(":cpn-sim"))
        }
    }
}
