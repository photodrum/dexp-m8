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

echo "==> окружение"
echo "    SDK:     $SDK"
echo "    JDK:     $JDK"

for f in "$BT/aapt2" "$AJ" "$KSTD"; do
    [ -e "$f" ] || { echo "нет $f — запусти tools/setup_sdk.sh"; exit 1; }
done

rm -rf "$OUT"
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
    --version-code 1 \
    --version-name 0.1.0 \
    -o "$OUT/stage/base.apk" \
    --java "$OUT/gen" \
    "$OUT/res/res.zip"

"$JDK/bin/javac" -encoding UTF-8 -source 8 -target 8 -nowarn \
    -cp "$AJ" -d "$OUT/cls" "$OUT/gen/ru/ogrpfsense/dexpm8/R.java" 2>/dev/null

echo "==> Kotlin"
"$KOTLINC" -cp "$AJ:$KSTD:$OUT/cls" -d "$OUT/cls" \
    -nowarn \
    $(find "$ROOT/app/src/main/java" -name '*.kt')

echo "==> dex"
(cd "$OUT/cls" && unzip -q -o "$KSTD" -x 'META-INF/*' 'kotlin/**' >/dev/null 2>&1 || true)
# d8 — шелл-обёртка, которая ищет java в PATH, поэтому PATH выставляем явно
PATH="$JDK/bin:$PATH" "$BT/d8" --release --min-api $MIN_SDK --lib "$AJ" \
    --output "$OUT/dex" \
    $(find "$OUT/cls" -name '*.class')

echo "==> упаковка"
cp "$OUT/dex/classes.dex" "$OUT/stage/classes.dex"
(cd "$OUT/stage" && zip -q -u base.apk classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/stage/base.apk" "$OUT/stage/aligned.apk"

KEYSTORE="$ROOT/tools/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
    echo "==> ключ отладки"
    "$JDK/bin/keytool" -genkeypair -keystore "$KEYSTORE" \
        -storepass dexpdebug -keypass dexpdebug -alias dexp \
        -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=DEXP M8 Debug' >/dev/null 2>&1
fi

"$BT/apksigner" sign \
    --ks "$KEYSTORE" --ks-pass pass:dexpdebug --key-pass pass:dexpdebug \
    --ks-key-alias dexp \
    --out "$OUT/app-debug.apk" "$OUT/stage/aligned.apk"

"$BT/apksigner" verify "$OUT/app-debug.apk"
echo "==> готово: $OUT/app-debug.apk ($(du -h "$OUT/app-debug.apk" | cut -f1))"

if [ "${1:-}" = "--install" ]; then
    echo "==> установка"
    "$SDK/platform-tools/adb" install -r -d "$OUT/app-debug.apk"
fi
