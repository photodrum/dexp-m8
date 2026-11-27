#!/usr/bin/env bash
# Сборка отладочного APK без Gradle.
#
# Зачем: Gradle тянет ~500 МБ зависимостей и AGP, а нам нужно проверять
# каждый шаг на железе. Здесь всё, что делает AGP по сути, — явно и за 10 секунд.
#
#   ./tools/build.sh            собрать app-debug.apk в build/
#   ./tools/build.sh --install   собрать и поставить на подключённую книгу
#
set -euo pipefail

# kotlinc падает OutOfMemoryError на shelf-экранах с умолчанием (~512 МБ);
# ставим проверенное значение сами, чтобы не помнить про переменную.
export JAVA_OPTS="${JAVA_OPTS:--Xmx1600m}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/build"
SDK="${ANDROID_HOME:-$HOME/.local/android-sdk}"
JDK="${JAVA_HOME:-$HOME/.local/jdk}"
KOTLINC="${KOTLINC:-$HOME/.local/kotlinc/bin/kotlinc}"
BT="$SDK/build-tools/34.0.0"
AJ="$SDK/platforms/android-34/android.jar"
KSTD="$HOME/.local/kotlinc/lib/kotlin-stdlib.jar"

MIN_SDK=27
TARGET_SDK=27
APP_ID=ru.ogrpfsense.dexpm8
# Держать в согласии с `app/build.gradle.kts` (п. 20): versionCode только вверх.
VERSION_CODE=8
VERSION_NAME=1.5.0

echo "==> окружение"
echo "    SDK:     $SDK"
echo "    JDK:     $JDK"

for f in "$BT/aapt2" "$AJ" "$KSTD"; do
    [ -e "$f" ] || { echo "нет $f — окружение в ~/.local, см. docs/BUILD.md («Окружение»)"; exit 1; }
done

# Чистим только свои подпапки: в build/ лежат ещё ref/ (эталоны Penpot) и shots/ (снимки с книги).
for d in res gen cls dex stage; do rm -rf "$OUT/$d"; done
rm -f "$OUT/app-debug.apk" "$OUT/app-debug.apk.idsig"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/cls" "$OUT/dex" "$OUT/stage"

echo "==> ресурсы"
"$BT/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$OUT/res/res.zip"

echo "==> манифест и R"
# aapt2 требует package в манифесте, а AGP 8 наоборот вырезает его оттуда
# (берёт из namespace). Поэтому подставляем в копию, оригинал не трогаем.
sed "s|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$APP_ID\">|" \
    "$ROOT/app/src/main/AndroidManifest.xml" > "$OUT/stage/AndroidManifest.xml"

"$BT/aapt2" link \
    -I "$AJ" \
    --manifest "$OUT/stage/AndroidManifest.xml" \
    --min-sdk-version $MIN_SDK \
    --target-sdk-version $TARGET_SDK \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    -o "$OUT/stage/base.apk" \
    --java "$OUT/gen" \
    "$OUT/res/res.zip"

"$JDK/bin/javac" -encoding UTF-8 -source 8 -target 8 -nowarn \
    -cp "$AJ" -d "$OUT/cls" "$OUT/gen/ru/ogrpfsense/dexpm8/R.java" 2>/dev/null

# BuildConfig генерирует только Gradle; здесь — заглушка как у debug (с 7.2 код его читает).
printf 'package ru.ogrpfsense.dexpm8;\npublic final class BuildConfig { public static final boolean DEBUG = true; }\n' \
    > "$OUT/gen/ru/ogrpfsense/dexpm8/BuildConfig.java"
"$JDK/bin/javac" -encoding UTF-8 -source 8 -target 8 -nowarn \
    -d "$OUT/cls" "$OUT/gen/ru/ogrpfsense/dexpm8/BuildConfig.java" 2>/dev/null

echo "==> Kotlin"
"$KOTLINC" -cp "$AJ:$KSTD:$OUT/cls" -d "$OUT/cls" \
    -nowarn \
    $(find "$ROOT/app/src/main/java" -name '*.kt')

echo "==> dex"
# Kotlin stdlib обязана попасть в dex: без неё на книге падает
# NoClassDefFoundError kotlin.jvm.internal.Lambda (лямбды и lazy в коде).
# d8 — шелл-обёртка, которая ищет java в PATH, поэтому PATH выставляем явно
PATH="$JDK/bin:$PATH" "$BT/d8" --release --min-api $MIN_SDK --lib "$AJ" \
    --output "$OUT/dex" \
    $(find "$OUT/cls" -name '*.class') "$KSTD"

echo "==> упаковка"
cp "$OUT/dex/classes.dex" "$OUT/stage/classes.dex"
(cd "$OUT/stage" && zip -q -u base.apk classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/stage/base.apk" "$OUT/stage/aligned.apk"

# Ключ — свой, тот же, что у Gradle (`~/.dexp-m8-keys/release.properties`, п. 20):
# подпись совпадает, и APK из build.sh ставится поверх APK из Gradle без удаления данных.
# Ключа нет — падаем в отладочный (старое поведение), но это уже другая подпись.
KEYSTORE=""
KS_PASS=""; KEY_PASS=""; KEY_ALIAS=""
KEYS="$HOME/.dexp-m8-keys/release.properties"
if [ -f "$KEYS" ]; then
    KEYSTORE="$(sed -n 's/^storeFile=//p' "$KEYS")"
    KS_PASS="$(sed -n 's/^storePassword=//p' "$KEYS")"
    KEY_ALIAS="$(sed -n 's/^keyAlias=//p' "$KEYS")"
    KEY_PASS="$(sed -n 's/^keyPassword=//p' "$KEYS")"
fi
if [ -z "$KEYSTORE" ] || [ ! -f "$KEYSTORE" ]; then
    echo "    ключа $KEYS нет — подпись отладочным (данные на книге не сохранятся при смене)"
    KEYSTORE="$HOME/.android/debug.keystore"
    if [ -f "$KEYSTORE" ]; then
        KS_PASS=android; KEY_PASS=android; KEY_ALIAS=androiddebugkey
    else
        KEYSTORE="$ROOT/tools/debug.keystore"
        KS_PASS=dexpdebug; KEY_PASS=dexpdebug; KEY_ALIAS=dexp
        if [ ! -f "$KEYSTORE" ]; then
            echo "==> ключ отладки"
            "$JDK/bin/keytool" -genkeypair -keystore "$KEYSTORE" \
                -storepass $KS_PASS -keypass $KEY_PASS -alias $KEY_ALIAS \
                -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=DEXP M8 Debug' >/dev/null 2>&1
        fi
    fi
fi

"$BT/apksigner" sign \
    --ks "$KEYSTORE" --ks-pass pass:$KS_PASS --key-pass pass:$KEY_PASS \
    --ks-key-alias $KEY_ALIAS \
    --out "$OUT/app-debug.apk" "$OUT/stage/aligned.apk"

"$BT/apksigner" verify "$OUT/app-debug.apk"
echo "==> готово: $OUT/app-debug.apk ($(du -h "$OUT/app-debug.apk" | cut -f1))"

if [ "${1:-}" = "--install" ]; then
    echo "==> установка"
    "$SDK/platform-tools/adb" install -r -d "$OUT/app-debug.apk"
fi
