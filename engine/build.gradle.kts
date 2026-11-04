// The on-device engines that need no Android: JavaScript/TypeScript (QuickJS +
// the web's own sucrase transpile) and Ruff (the official static binary, run as
// a process). Plain JVM, so the same code is tested on the build machine.
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core"))
    // The KMP root artifact: an Android app resolves its Android variant, this module's tests the JVM one.
    implementation(libs.quickjs)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

tasks.test {
    // The Ruff tests run the real binary when scripts/fetch-ruff.sh has put one for this machine here.
    systemProperty("ruff.bin", rootProject.file("build/ruff-host/ruff").absolutePath)
    testLogging { events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
