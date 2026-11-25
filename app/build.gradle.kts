plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "cn.shiying.local"
    compileSdk = 35
    defaultConfig {
        applicationId = "cn.shiying.local"
        minSdk = 29
        targetSdk = 35
        versionCode = 21
        versionName = "0.9.9"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += (providers.gradleProperty("shiyingAbis").orNull ?: "arm64-v8a").split(",") }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            // Local delivery keeps upgrade compatibility with previously installed development APKs.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/documentAssets"))
    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/documentJni"))
    packaging.jniLibs.useLegacyPackaging = true
    packaging.jniLibs.pickFirsts += "**/libc++_shared.so"
}
val prepareDocuments by tasks.registering(Exec::class) {
    inputs.file(rootProject.file("tools/prepare_documents.py"))
    inputs.dir(rootProject.file("third_party/matting"))
    inputs.dir(rootProject.file("third_party/scanning"))
    outputs.dir(layout.buildDirectory.dir("generated/documentAssets"))
    outputs.dir(layout.buildDirectory.dir("generated/documentJni"))
    commandLine(System.getenv("SHIYING_PYTHON") ?: "python", rootProject.file("tools/prepare_documents.py").absolutePath)
}
tasks.named("preBuild") { dependsOn(prepareDocuments) }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.9")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        // Certificate signing/TLS helpers are not used by local text extraction.
        exclude(group = "org.bouncycastle")
    }
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    implementation("org.opencv:opencv:4.12.0")
    implementation("androidx.camera:camera-camera2:1.5.2")
    implementation("androidx.camera:camera-lifecycle:1.5.2")
    implementation("androidx.camera:camera-view:1.5.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
