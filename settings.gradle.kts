pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "headless-cpn"

include(
    ":cpn-core",
    ":cpn-lang",
    ":cpn-sim",
    ":cpn-statespace",
    ":cpn-io",
    ":cpn-api",
    ":cpn-cli",
    ":cpn-app",
)
