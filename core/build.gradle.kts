plugins {
    id("com.android.library") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "2.0.20"
}

android {
    namespace = "de.jarvis.w204.core"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")
    testImplementation("junit:junit:4.13.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
