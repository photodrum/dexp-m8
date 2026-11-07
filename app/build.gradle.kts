plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.ogrpfsense.dexpm8"
    compileSdk = 34
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "ru.ogrpfsense.dexpm8"
        minSdk = 27
        targetSdk = 27
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // PX30 = Cortex-A35, только arm64-v8a. x86 оставляем для эмулятора.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=none")
                cFlags += listOf("-std=c99", "-Os", "-ffunction-sections", "-fdata-sections", "-Wall")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            ndk {
                // 16 ГБ flash, но ставим только то, что нужно устройству
                abiFilters += listOf("arm64-v8a")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
        // Никаких рефлексий и лишних метаданных: экономия RAM и размера dex
        freeCompilerArgs += listOf("-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions")
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.version", "kotlin/**", "DebugProbesKt.bin")
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // Осознанно пусто: ни AndroidX, ни Material, ни Kotlin stdlib сверх того,
    // что даёт компилятор. Всё UI — на android.* и Canvas.
}
