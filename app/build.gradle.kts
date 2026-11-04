import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// One place for the client config (ETC): app/benchjot.properties.
val config = Properties().apply { file("benchjot.properties").inputStream().use(::load) }
fun cfg(key: String): String = requireNotNull(config.getProperty(key)) { "benchjot.properties is missing $key" }
val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "app.benchjot"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.benchjot"
        minSdk = 26
        targetSdk = 35
        versionCode = runNumber
        versionName = "0.1.$runNumber"
        buildConfigField("String", "SUPABASE_URL", "\"${cfg("supabaseUrl")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${cfg("supabaseAnonKey")}\"")
        buildConfigField("String", "WEB_URL", "\"${cfg("webUrl")}\"")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/io.netty.versions.properties") } }
}

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
    implementation(libs.webkit)
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.markdown.m3)
    testImplementation(libs.junit)
}
