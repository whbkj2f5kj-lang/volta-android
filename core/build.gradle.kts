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
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    testImplementation("junit:junit:4.13.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.herohan:UVCAndroid:1.0.13")
}

// Official, version-pinned model; packaged for offline operation, never downloaded on the radio.
val vehicleModel = layout.buildDirectory.file("generated/vision-assets/efficientdet_lite0.tflite")
val prepareVisionModel by tasks.registering {
    outputs.file(vehicleModel)
    doLast {
        val dest = vehicleModel.get().asFile
        val expected = "0720bf247bd76e6594ea28fa9c6f7c5242be774818997dbbeffc4da460c723bb"
        val url = "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite"
        val connection = java.net.URL(url).openConnection().apply { connectTimeout = 20_000; readTimeout = 30_000 }
        val bytes = connection.getInputStream().use { it.readBytes() }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(digest == expected) { "Unexpected vehicle model checksum" }
        dest.parentFile.mkdirs()
        dest.writeBytes(bytes)
    }
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/vision-assets"))
tasks.named("preBuild") { dependsOn(prepareVisionModel) }
