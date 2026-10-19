import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from keystore.properties, which is git-ignored.
// Without it, release builds fall back to the debug key so `assembleRelease`
// still produces an installable APK.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.meetdheeran.notch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.meetdheeran.notch"
        // Android 12: RenderEffect (the island's liquid necks) and the accessibility overlay above the status bar.
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        vectorDrawables.useSupportLibrary = true
        // Face unlock's native libraries (MediaPipe, ONNX Runtime, TensorFlow Lite): phones only, no emulators.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; aidl = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    // The face models are memory-mapped straight out of the APK, so they must not be compressed.
    androidResources { noCompress += listOf("tflite", "onnx", "task") }
}

// Face unlock's models (Morse's, ported) are fetched by the build, not kept in git (FaceNet-512 alone is 94 MB), and
// each is checked against its SHA-256. A copy in Morse's checkout beside this one is used before downloading.
// Sources and licences: docs/licenses/FACE-MODELS.md.
data class FaceModel(val name: String, val url: String, val size: Long, val sha256: String)
val faceModels = listOf(
    FaceModel(
        "face_landmarker.task",
        "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task",
        3_758_596, "64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff",
    ),
    FaceModel(
        "facenet512_weights.onnx",
        "https://github.com/serengil/deepface_models/releases/download/v1.0/facenet512_weights.onnx",
        93_956_456, "a7a4fbcc6af3175f5c4e91de0b92b4fa387e0e9f141efd59616337220363da33",
    ),
    FaceModel(
        "spoof_model_scale_2_7.tflite",
        "https://raw.githubusercontent.com/shubham0204/OnDevice-Face-Recognition-Android/f6405d0c316a1845893c4961d492cd9a8e765934/app/src/main/assets/spoof_model_scale_2_7.tflite",
        5_979_004, "5d573d20b795a19d7b2a0f67cc4c1130ce8ec30d5f6aa06b2c788a344a49d4dd",
    ),
    FaceModel(
        "spoof_model_scale_4_0.tflite",
        "https://raw.githubusercontent.com/shubham0204/OnDevice-Face-Recognition-Android/f6405d0c316a1845893c4961d492cd9a8e765934/app/src/main/assets/spoof_model_scale_4_0.tflite",
        6_433_552, "32eecfa06cd0cf3c514276fb8521ed820fc256bacf4c616a1f05612d1c156463",
    ),
)

fun sha256(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

val fetchFaceModels by tasks.registering {
    description = "Puts face unlock's models in assets/models (once; each checked by SHA-256): from Morse's checkout if it's beside this one, else downloaded."
    val dir = file("src/main/assets/models")
    val nearby = listOf(rootProject.file("../morse/app/src/main/assets/models"))
    outputs.files(faceModels.map { File(dir, it.name) })
    doLast {
        dir.mkdirs()
        for (m in faceModels) {
            val f = File(dir, m.name)
            if (f.length() == m.size) continue
            val part = File(dir, "${m.name}.part")
            val local = nearby.map { File(it, m.name) }.firstOrNull { it.length() == m.size }
            if (local != null) {
                logger.lifecycle("Notch: copying ${m.name} from ${local.parentFile}")
                local.copyTo(part, overwrite = true)
            } else {
                logger.lifecycle("Notch: downloading ${m.name}")
                URI(m.url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
            }
            check(sha256(part) == m.sha256) { "${m.name}: checksum mismatch, not using it" }
            f.delete()
            check(part.renameTo(f)) { "${m.name}: couldn't move it into place" }
        }
    }
}
tasks.named("preBuild") { dependsOn(fetchFaceModels) }

dependencies {
    testImplementation("junit:junit:4.13.2")
    val bom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // The vault's face unlock (ported from Morse): the front camera; MediaPipe tracks the face (478 points, on the
    // GPU); FaceNet-512 on ONNX Runtime says whose face it is; MiniFASNet on TensorFlow Lite tells a real face from a
    // photo or a screen.
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mediapipe:tasks-vision:0.10.20")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
}
