import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Ключ подписи — вне репозитория (п. 20): `~/.dexp-m8-keys/release.properties`
 * (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`, права 600).
 *
 * Один ключ и для debug, и для release. Зачем: Android пускает APK поверх
 * установленного только при совпадающей подписи. Пока release был неподписан,
 * а debug — ключом `~/.android/debug.keystore`, перейти на свой ключ можно было
 * лишь удалением приложения вместе с базой и настройками. С общим ключом
 * переход делается один раз (старая debug-сборка → наша), дальше debug и release
 * ставятся друг поверх друга без потери данных.
 *
 * Файла нет — release собирается неподписанным (`app-release-unsigned.apk`),
 * сборка не ломается: ключ нужен только для выпуска версии.
 */
val dexpKeyFile = File(System.getProperty("user.home"), ".dexp-m8-keys/release.properties")
val dexpKey: Properties? =
    if (dexpKeyFile.isFile) Properties().apply { dexpKeyFile.inputStream().use { load(it) } } else null

android {
    namespace = "ru.ogrpfsense.dexpm8"
    compileSdk = 34
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "ru.ogrpfsense.dexpm8"
        minSdk = 27
        targetSdk = 27
        // versionCode только вверх: B43 сравнивает его с `update.json`, а Android —
        // при установке (`INSTALL_FAILED_VERSION_DOWNGRADE`). П. 20 — первый
        // подписанный выпуск, поэтому 2 (0.1.0 ходила без подписи).
        versionCode = 9
        versionName = "1.5.1"

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

    /**
     * Подпись (п. 20). Один ключ на debug и release — см. комментарий к [dexpKey] выше.
     * Ключа нет — `signingConfig` не создаётся, release собирается неподписанным.
     */
    signingConfigs {
        if (dexpKey != null) {
            create("dexp") {
                storeFile = File(dexpKey.getProperty("storeFile"))
                storePassword = dexpKey.getProperty("storePassword")
                keyAlias = dexpKey.getProperty("keyAlias")
                keyPassword = dexpKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("dexp")
        }
        debug {
            // Тот же ключ, что у release: иначе смена подписи при выпуске версии
            // стирает данные приложения на книге (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).
            signingConfig = signingConfigs.findByName("dexp")
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
