plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.android.built.in1.kotlin)
    id("org.jetbrains.kotlin.plugin.compose")
}

// Same signing contract as :app — local keystore.properties or CI env vars.
val keystoreFile = rootProject.file("keystore.properties")
val keystoreProps: Map<String, String> = if (keystoreFile.exists()) {
    keystoreFile.readLines()
        .filter { '=' in it && !it.trimStart().startsWith("#") }
        .associate { line ->
            val parts = line.split('=', limit = 2)
            parts[0].trim() to parts[1].trim()
        }
} else emptyMap()
fun signEnv(name: String): String? = keystoreProps[name] ?: System.getenv(name)

android {
    namespace = "com.nukirk.medrx"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nukirk.medrx"
        minSdk = 30
        targetSdk = 37
        versionCode = 24
        versionName = "2.2.2-fork.1"
    }

    signingConfigs {
        create("release") {
            val storePath = signEnv("KEY_STORE_FILE")
            if (storePath != null) {
                storeFile = rootProject.file(storePath)
                storePassword = signEnv("KEY_STORE_PASSWORD")
                keyAlias = signEnv("ALIAS")
                keyPassword = signEnv("KEY_PASSWORD") ?: signEnv("KEY_STORE_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.play.services.wearable)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.androidx.compose.navigation)
    implementation(libs.androidx.compose.ui.v1110)
    implementation(libs.androidx.compose.ui.tooling.preview.v1110)
    implementation(libs.androidx.compose.material.icons.extended.v161)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
}