plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version comes from the CI run number: -PversionCode=... -PversionName=...
val ciVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
val ciVersionName = (project.findProperty("versionName") as String?) ?: "1.0.0"

android {
    namespace = "com.masahanew.dxfsurvey"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.masahanew.dxfsurvey"
        minSdk = 26
        targetSdk = 34
        versionCode = ciVersionCode
        versionName = ciVersionName
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
