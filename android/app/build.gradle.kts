plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val serverUrl = (project.findProperty("remoteHelpServer") as String?) ?: "https://CHANGE-ME.example.com"

android {
    namespace = "ua.remotehelp"
    compileSdk = 35

    defaultConfig {
        applicationId = "ua.remotehelp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"$serverUrl\"")
    }

    // Release signing: set RH_KEYSTORE_FILE, RH_KEYSTORE_PASSWORD, RH_KEY_ALIAS, RH_KEY_PASSWORD
    // (env vars). Without them the release APK is signed with the debug key.
    val keystore = System.getenv("RH_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("RH_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RH_KEY_ALIAS")
                keyPassword = System.getenv("RH_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
