plugins {
    id("com.android.application")
}

val visionAssetsDir = layout.buildDirectory.dir("generated/crewvision-assets")

android {
    namespace = "com.crew.car"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.crew.car"
        minSdk = 26
        targetSdk = 35
        versionCode = 23
        versionName = "0.1.22"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/crewvision-assets"))
    androidResources { noCompress += "tflite" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.32")
    testImplementation("junit:junit:4.13.2")
}

/** Download the official 320x320 person-capable EfficientDet model at build time,
 *  not on the offline car Wi-Fi. Fail closed if it is missing or not a TFLite file.
 *  Cached under build/; run Gradle online for the initial APK build.
 */
val prepareCrewVisionModel by tasks.registering {
    val model = visionAssetsDir.map { it.file("efficientdet_lite0.tflite") }
    outputs.file(model)
    doLast {
        val target = model.get().asFile
        fun valid(file: java.io.File): Boolean =
            file.exists() && file.length() > 1_000_000L &&
                file.inputStream().use { stream ->
                    val magic = ByteArray(8)
                    stream.read(magic) == 8 &&
                        String(magic, 4, 4, Charsets.US_ASCII) == "TFL3"
                }
        if (!valid(target)) {
            target.parentFile.mkdirs()
            val temp = java.io.File(target.parentFile, target.name + ".part")
            try {
                val url = java.net.URL(
                    "https://storage.googleapis.com/mediapipe-models/" +
                    "object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite")
                val connection = url.openConnection()
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.getInputStream().use { input ->
                    temp.outputStream().buffered().use { output ->
                        input.copyTo(output)
                    }
                }
                require(valid(temp)) { "Crew Vision model download invalid; refusing to package an unusable model" }
                java.nio.file.Files.move(temp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { temp.delete() }
        }
        require(valid(target)) { "Crew Vision detector model unavailable: build requires Internet once" }
    }
}
tasks.named("preBuild") { dependsOn(prepareCrewVisionModel) }
// Ensure asset packaging cannot race against model generation on parallel Gradle builds.
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets"))
        dependsOn(prepareCrewVisionModel)
}
