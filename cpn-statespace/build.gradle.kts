// Reachability graph, SCC graph and state-space reports.
plugins {
    id("cpn.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":cpn-sim"))
        }
    }
}
