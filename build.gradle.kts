plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "id.hendrik.pdfttd"
    compileSdk = 34

    defaultConfig {
        applicationId = "id.hendrik.pdfttd"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
