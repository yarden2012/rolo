plugins {
    id("com.android.application")
}

android {
    namespace = "dev.rolo.reflex"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.rolo.reflex"
        minSdk = 24
        targetSdk = 35
        versionCode = 5
        versionName = "1.1.2"
    }

    signingConfigs {
        // A project-wide debug key, committed on purpose: it is the standard public
        // Android debug key format (password "android"), not a secret. Using one
        // fixed key means an APK built on any machine or in CI installs as an
        // update over another, so your training history is never lost to a
        // signature mismatch. Make your own release key before publishing anywhere.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests {
            // Robolectric tests render the real layouts and drills, so they need the resources.
            isIncludeAndroidResources = true
            all { it.maxHeapSize = "3g" }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
}
