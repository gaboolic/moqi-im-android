plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.moqi.im"
    compileSdk = 34

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.moqi.im"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    flavorDimensions += "bundle"
    productFlavors {
        create("full") {
            dimension = "bundle"
            buildConfigField("boolean", "VOICE_INPUT_ENABLED", "true")
        }
        create("lite") {
            dimension = "bundle"
            applicationIdSuffix = ".lite"
            versionNameSuffix = "-lite"
            buildConfigField("boolean", "VOICE_INPUT_ENABLED", "false")
        }
    }

    sourceSets {
        getByName("main") {
            assets {
                srcDirs("src/main/assets", "src/main/models")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    add("fullImplementation", files("libs/sherpa-onnx-1.13.0.aar"))
    implementation(files("libs/moqi-ime.aar"))
    testImplementation("junit:junit:4.13.2")
}

// 应用模型下载脚本（可选：构建时自动下载模型）
// apply(from = "download-models.gradle.kts")