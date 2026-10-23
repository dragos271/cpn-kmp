// Enabling, binding search, firing and simulation.
plugins {
    id("cpn.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":cpn-core"))
            api(project(":cpn-lang"))
        }
    }
}
