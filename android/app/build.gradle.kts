import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing: CI passes the key through env vars (see
// .github/workflows/release.yml); locally it is read from
// ~/.android-keys/vivohihz-release.properties. Neither lives in the repo.
val signing: Map<String, String>? = run {
    System.getenv("KEYSTORE_FILE")?.let { file ->
        return@run mapOf(
            "KEYSTORE" to file,
            "KEY_ALIAS" to System.getenv("KEY_ALIAS"),
            "KEYSTORE_PASSWORD" to System.getenv("KEYSTORE_PASSWORD"),
            "KEY_PASSWORD" to System.getenv("KEY_PASSWORD"),
        )
    }
    val local = File(System.getProperty("user.home"), ".android-keys/vivohihz-release.properties")
    if (!local.isFile) return@run null
    val p = Properties().apply { local.inputStream().use { load(it) } }
    p.stringPropertyNames().associateWith { p.getProperty(it) }
}

android {
    namespace = "jp.androplus.vivohz"
    compileSdk = 36

    defaultConfig {
        applicationId = "jp.androplus.vivohz"
        minSdk = 29
        targetSdk = 36
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0"
    }

    signingConfigs {
        if (signing != null) {
            create("release") {
                storeFile = file(signing.getValue("KEYSTORE"))
                storePassword = signing.getValue("KEYSTORE_PASSWORD")
                keyAlias = signing.getValue("KEY_ALIAS")
                keyPassword = signing.getValue("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (signing != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Optional watcher for the app in front; the accessibility one needs neither.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.2")
}
