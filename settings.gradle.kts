pluginManagement {
    plugins {
        kotlin("jvm") version "2.4.21"
        kotlin("plugin.serialization") version "2.4.21"
        id("com.android.application") version "9.5.0"
    }
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "UniEar"
include(":core", ":desktop")
if (!providers.gradleProperty("desktopOnly").isPresent) include(":android")
