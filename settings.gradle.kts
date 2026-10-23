pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
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
)
