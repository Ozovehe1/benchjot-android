// The on-device engine that needs no Android: Ruff (the official static binary, run as a
// process). Plain JVM, so the same code is tested on the build machine.
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core"))
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

tasks.test {
    // The Ruff tests run the real binary when scripts/fetch-ruff.sh has put one for this machine here.
    systemProperty("ruff.bin", rootProject.file("build/ruff-host/ruff").absolutePath)
    testLogging { events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
