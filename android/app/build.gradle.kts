plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.zorail.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.zorail.android"
        minSdk = 26        // gomobile's floor in practice; also where notification channels land
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // The Go .aar is already stripped by gomobile; R8 only touches Kotlin.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    // The Go core ships as an .aar built by `make android-aar` into app/libs/.
    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    // zorail.aar — produced by `make android-aar`, not checked in.
    implementation(files("libs/zorail.aar"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
}
