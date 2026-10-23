// Public facade over the engine for JVM, JS and native callers.
plugins {
    id("cpn.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":cpn-io"))
            api(project(":cpn-sim"))
            api(project(":cpn-statespace"))
        }
    }
}
