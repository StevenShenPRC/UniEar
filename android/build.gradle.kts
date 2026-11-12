plugins {
    id("com.android.application")
}

android {
    namespace = "uniear.android"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.uniear.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies { implementation(project(":core")) }
