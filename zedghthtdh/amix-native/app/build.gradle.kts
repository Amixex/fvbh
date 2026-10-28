plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

// Если рядом лежит google-services.json (скачан из Firebase для приложения com.amix.tutor),
// приложение использует его: это надёжнее, чем настройки сайта.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.amix.tutor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.amix.tutor"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "2.0.0-alpha1"

        // Только 64-битные телефоны: APK меньше
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Модель в assets не сжимается: она уже сжата, а читать её так быстрее
    androidResources {
        noCompress += listOf("task")
    }

    // Постоянный отладочный ключ: отпечаток SHA не меняется между сборками, поэтому вход через Google
    // достаточно зарегистрировать в Firebase один раз. Для RuStore будет отдельный release-ключ.
    signingConfigs {
        create("amixDebug") {
            storeFile = file("amix-debug.p12")
            storePassword = "amixdebug"
            keyAlias = "amix"
            keyPassword = "amixdebug"
            storeType = "pkcs12"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("amixDebug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")

    implementation("com.google.mediapipe:tasks-genai:0.10.24")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Навигация, внедрение зависимостей, база данных
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("com.google.dagger:hilt-android:2.51.1")
    ksp("com.google.dagger:hilt-android-compiler:2.51.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")

    // Вход (Firebase Auth без Google Play Services на критичном пути) и аватарки
    implementation(platform("com.google.firebase:firebase-bom:33.4.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("io.coil-kt:coil-compose:2.7.0")
    ksp("androidx.room:room-compiler:2.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")
}