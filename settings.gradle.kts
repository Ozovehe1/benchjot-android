pluginManagement {
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
rootProject.name = "benchjot-android"
include(":core")
// The Android app needs the Android SDK; `-PcoreOnly` builds and tests the pure core without it.
if (!providers.gradleProperty("coreOnly").isPresent) include(":app")
