plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sqmnet.bishoppocket"
    compileSdk = 34

    signingConfigs {
        // Firma ESTABLE del proyecto: bishop-pocket.keystore (copia del debug original).
        // Nunca usar el debug.keystore del sistema (se regenera y rompe las actualizaciones).
        create("pocket") {
            storeFile = rootProject.file("bishop-pocket.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.sqmnet.bishoppocket"
        minSdk = 26
        targetSdk = 34
        versionCode = 23
        versionName = "1.13"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("pocket")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
