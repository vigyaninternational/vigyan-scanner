plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vigyan.scanner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vigyan.scanner"
        minSdk = 24
        targetSdk = 34
        // CI passes these (run number / 1.0.<run number>) so every new APK installs over the old one.
        versionCode = (System.getenv("VERSION_CODE") ?: "").toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.0.0"
        vectorDrawables { useSupportLibrary = true }
        // Only the processor types real phones use: keeps the Odia reader (native code) small.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        // Fixed, committed debug key: every build is signed the same, so updates install over the top.
        getByName("debug") {
            storeFile = rootProject.file("config/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Store native code compressed: a much smaller download (the Odia reader is about 12 MB uncompressed).
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Page thumbnails
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Google's document scanner: camera, edge detection, crop, filters, gallery import, multi-page.
    // Runs inside Google Play services, so it adds almost nothing to the APK.
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
    // OCR (reads printed English text), also through Google Play services.
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    // Hindi (Devanagari) OCR; it reads English too.
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-devanagari:16.0.1")
    // QR codes (the Aadhaar QR).
    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1")
    // Passport photo: face position, and the person vs background (for a white background).
    implementation("com.google.android.gms:play-services-mlkit-face-detection:17.1.0")
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
    // Odia text reading (Tesseract). The Odia + English data (about 5.5 MB) downloads on first use.
    implementation("com.github.adaptech-cz.Tesseract4Android:tesseract4android:4.8.0")
    // Fingerprint unlock for the app lock (needs a FragmentActivity).
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    // Quick scan camera.
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    testImplementation("junit:junit:4.13.2")
    // Opens the PDFs PdfWriter makes, to check text and password in tests.
    testImplementation("org.apache.pdfbox:pdfbox:2.0.32")
    // Android's org.json is only a stub in plain unit tests; the real one lets the JSON code be tested.
    testImplementation("org.json:json:20240303")
}
