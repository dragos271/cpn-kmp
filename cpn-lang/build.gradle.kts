// Inscription languages (CPN ML and Python-like), type checker and evaluator.
plugins {
    id("cpn.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":cpn-core"))
        }
    }
}
