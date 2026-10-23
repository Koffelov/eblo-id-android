plugins {
    id("com.android.application")
}

// Release signing comes only from the environment (GitHub Actions secrets).
// Nothing secret is stored in the repository.
val signingStoreFile: String? = System.getenv("SIGNING_STORE_FILE")?.takeIf { it.isNotBlank() }

android {
    namespace = "io.github.koffelov.ebloclient"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.koffelov.ebloclient"
        minSdk = 24
        targetSdk = 36
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.0.0-local"
    }

    signingConfigs {
        if (signingStoreFile != null) {
            create("release") {
                storeFile = file(signingStoreFile)
                storeType = "pkcs12"
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Without the secrets the release APK stays unsigned; CI refuses to publish it.
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            // A debug build installs next to the release build and never replaces it.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
