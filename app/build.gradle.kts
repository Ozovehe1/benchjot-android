import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.chaquopy)
}

// One place for the client config (ETC): app/benchjot.properties.
val config = Properties().apply { file("benchjot.properties").inputStream().use(::load) }
fun cfg(key: String): String = requireNotNull(config.getProperty(key)) { "benchjot.properties is missing $key" }
val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "app.benchjot"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.benchjot"
        minSdk = 26
        targetSdk = 35
        versionCode = runNumber
        versionName = "0.1.$runNumber"
        buildConfigField("String", "SUPABASE_URL", "\"${cfg("supabaseUrl")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${cfg("supabaseAnonKey")}\"")
        buildConfigField("String", "WEB_URL", "\"${cfg("webUrl")}\"")
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"${cfg("googleServerClientId")}\"")
        // Python, Ruff and QuickJS are native, one copy per ABI. A plain build is the phone APK:
        // 64-bit and 32-bit ARM in one (universal). CI also builds -Pabi=x86_64 for the emulator
        // and -Pabi=armeabi-v7a to run the 32-bit engines under the emulator's ARM translation.
        ndk { abiFilters += (providers.gradleProperty("abi").orNull?.split(",") ?: listOf("arm64-v8a", "armeabi-v7a")) }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // A fixed sideload key, committed on purpose so every CI build installs over the
    // last one. It is NOT a Play Store key; replace it before publishing anywhere.
    signingConfigs {
        create("sideload") {
            storeFile = file("debug-ci.keystore")
            storePassword = "benchjot"
            keyAlias = "benchjot"
            keyPassword = "benchjot"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("sideload") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    // The instrumented Ruff test checks the device binary against the web fixture the JVM tests use.
    sourceSets["androidTest"].resources.srcDir("../engine/src/test/resources")

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Ruff ships as lib/<abi>/libruff.so; it has to be unpacked to the native library dir to run.
    packaging { jniLibs { useLegacyPackaging = true } }
    packaging { resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/io.netty.versions.properties") } }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(project(":engine"))
    implementation(libs.credentials)
    implementation(libs.credentials.play)
    implementation(libs.googleid)
    implementation(libs.jlatexmath)
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.storage)
    implementation(libs.androidsvg)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.markdown.m3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

// Embedded CPython for on-device runs (PythonService). Packages are bundled at build
// time; the web's Pyodide loads others on demand, which a phone without pip can't.
chaquopy {
    defaultConfig {
        version = "3.13"
        pip {
            // Native packages for armeabi-v7a are built by scripts/build-python-armv7.sh (Chaquopy's
            // index has cp313 for 64-bit only); the pins are the versions its recipes build, so both
            // ABIs get the same ones (one list: scripts/python-armv7-packages.txt).
            options("--find-links", rootProject.file("build/python-armv7/wheels").path)
            rootProject.file("scripts/python-armv7-packages.txt").readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
                .forEach { val (name, version) = it.split(Regex("\\s+")); install("$name==$version") }
            // Pure Python: plotting libraries the capture module knows. Bokeh < 3.4 because 3.4+ needs
            // contourpy >= 1.2, newer than Chaquopy's recipe; jsonschema < 4.18 avoids rpds-py (Rust).
            install("plotly")
            install("bokeh<3.4")
            install("seaborn")
            install("altair")
            install("jsonschema<4.18")
            // pyparsing 3.3 deprecates the camelCase names matplotlib 3.8 calls, so every import printed warnings.
            install("pyparsing<3.3")
        }
    }
}

// The Ruff binaries the APK bundles (scripts/build-ruff-android.sh); a build without them would ship no Autofix.
tasks.named("preBuild") {
    doFirst {
        for (abi in android.defaultConfig.ndk.abiFilters) {
            check(file("src/main/jniLibs/$abi/libruff.so").exists()) { "Missing Ruff for $abi: run scripts/build-ruff-android.sh" }
        }
    }
}
