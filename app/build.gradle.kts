plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.islandblast.game"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.islandblast.game"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.5.0"
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
    kotlinOptions {
        jvmTarget = "17"
    }
    androidResources {
        // Keep the reference-derived PNGs bit-exact.
        noCompress += listOf("png")
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "3g"
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.systemProperty("level5.shots", System.getProperty("level5.shots") ?: "")
                it.systemProperty("level5.repo", rootProject.projectDir.absolutePath)
            }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
