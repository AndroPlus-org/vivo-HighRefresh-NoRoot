plugins {
    id("com.android.application")
}

android {
    namespace = "jp.androplus.vivohz"
    compileSdk = 36

    defaultConfig {
        applicationId = "jp.androplus.vivohz"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {}
