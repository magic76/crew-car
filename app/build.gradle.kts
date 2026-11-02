plugins {
    id("com.android.application")
}

android {
    namespace = "com.crew.car"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.crew.car"
        minSdk = 26
        targetSdk = 35
        versionCode = 20
        versionName = "0.1.19"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
