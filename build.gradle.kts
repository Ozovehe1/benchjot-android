plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.chaquopy) apply false
}

// Local builds can keep outputs out of the checkout: BJ_BUILD_ROOT=/tmp/bj ./gradlew ...
System.getenv("BJ_BUILD_ROOT")?.let { root -> allprojects { layout.buildDirectory.set(file("$root/${project.path.replace(':', '/')}")) } }
