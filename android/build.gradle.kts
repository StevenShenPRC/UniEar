plugins {
    id("com.android.application")
}

android {
    namespace = "uniear.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.uniear.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "0.3.2"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies { implementation(project(":core")) }
