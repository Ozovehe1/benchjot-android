// Python 3.13 on 32-bit ARM (armeabi-v7a): Chaquopy publishes it for 64-bit ABIs only, so
// scripts/build-python-armv7.sh builds it (CPython, Chaquopy's runtime, numpy, and the plugin
// with armeabi-v7a allowed) into build/python-armv7/maven, which alone serves those modules.
// The plugin loads its runtime and Python from its own (plugin) repositories, so they are here.
pluginManagement {
    repositories {
        val local = settingsDir.resolve("build/python-armv7/maven")
        if (local.isDirectory) exclusiveContent { // absent only for -PcoreOnly (checked below)
            forRepository { maven(url = local) { name = "pythonArmv7" } }
            filter {
                includeModule("com.chaquo.python", "gradle")
                includeModule("com.chaquo.python", "target")
                includeModule("com.chaquo.python.runtime", "chaquopy")
                includeModule("com.chaquo.python.runtime", "libchaquopy_java")
            }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
val coreOnly = providers.gradleProperty("coreOnly").isPresent
if (!coreOnly && !file("build/python-armv7/maven").isDirectory) {
    throw GradleException("Missing build/python-armv7/maven: run scripts/build-python-armv7.sh (CI: the python-armv7 job's artifact)")
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
include(":engine")
// The Android app needs the Android SDK; `-PcoreOnly` builds and tests the pure modules (core, engine) without it.
if (!coreOnly) include(":app")
