plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: prefers a local keystore.properties (storeFile, storePassword,
// keyAlias, keyPassword), falling back to environment variables (KEY_STORE_FILE,
// KEY_STORE_PASSWORD, ALIAS, KEY_PASSWORD) for CI. Never commit real credentials.
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
        minSdk = 26
        targetSdk = 37
        versionCode = 23
        versionName = "2.2.1-fork.1"
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3.window.size.class1)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.play.services.wearable)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.palette.ktx)
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.compose.markdown)
    implementation(libs.androidx.biometric)
    implementation(libs.coil.compose)
    implementation(libs.material)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
}