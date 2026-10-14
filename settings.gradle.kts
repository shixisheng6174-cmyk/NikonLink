// settings.gradle.kts
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "NikonLink"
include(":app")
include(":core-common")
include(":core-data")
include(":feature-connection")
include(":feature-browser")
include(":feature-transfer")
include(":feature-remote")
