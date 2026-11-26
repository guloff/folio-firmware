import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Пароли и путь к keystore — из keystore.properties (в git не попадает).
// Нет файла → release собирается неподписанным, но сборка не падает.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val hasKeystore = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.rustamguloff.foliolink"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "com.rustamguloff.foliolink"
        // java.time и адаптивные иконки без desugaring.
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "1.1"
        // Каталог словарей (манифест релиза dictionaries-1). Отладочную сборку можно направить на
        // локальный сервер: -PdictionaryCatalogUrl=http://10.0.2.2:8000/manifest.json или настройка
        // «Каталог словарей» в «Настройках приложения» (видна только в debug).
        buildConfigField("String", "DICTIONARY_CATALOG_URL",
            "\"https://github.com/guloff/folio-firmware/releases/download/dictionaries-1/manifest.json\"")
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            (project.findProperty("dictionaryCatalogUrl") as String?)?.takeIf { it.isNotBlank() }?.let {
                buildConfigField("String", "DICTIONARY_CATALOG_URL", "\"$it\"")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        localeFilters += listOf("en", "ru")
        // Выбор языка приложения в системных настройках (Android 13+).
        generateLocaleConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        // Material 3: TopAppBar, ModalBottomSheet, DatePicker и др. ещё помечены экспериментальными.
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.glance:glance-appwidget:1.2.0")
    implementation("androidx.glance:glance-material3:1.2.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // Ed25519 (подпись прошивки) — в платформе только с API 33, а minSdk 26.
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
