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

    testImplementation("junit:junit:4.13.2")
}
