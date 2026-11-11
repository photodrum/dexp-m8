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
            // Прошивка книги может быть 32-битной даже на 64-битном PX30:
            // без armeabi-v7a установка падает с INSTALL_FAILED_NO_MATCHING_ABIS.
            // Прошивка 32-битная (ARCHITECTURE.md) — только armeabi-v7a, и в debug, и в release.
            abiFilters += listOf("armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                // c++_static: движки читалки (crengine, djvulibre) на C++; dexp_core остаётся на C99.
                arguments += listOf("-DANDROID_STL=c++_static")
                cFlags += listOf("-Os", "-ffunction-sections", "-fdata-sections", "-Wall")
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
