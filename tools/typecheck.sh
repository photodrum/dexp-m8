#!/usr/bin/env bash
# Проверка Kotlin-кода без Android: подсовываем android.jar как есть.
# Ловит ошибки типов и опечатки до сборки на устройстве.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/.local/android-sdk}"
JDK="${JAVA_HOME:-$HOME/.local/jdk}"
KOTLINC="${KOTLINC:-$HOME/.local/kotlinc/bin/kotlinc}"
AJ="$SDK/platforms/android-34/android.jar"
KSTD="$HOME/.local/kotlinc/lib/kotlin-stdlib.jar"
OUT="$ROOT/build/typecheck"

rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/cls" "$OUT/kt"

BT="$SDK/build-tools/34.0.0"
"$BT/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$OUT/res.zip"
sed "s|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"ru.ogrpfsense.dexpm8\">|" \
    "$ROOT/app/src/main/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
"$BT/aapt2" link -I "$AJ" --manifest "$OUT/AndroidManifest.xml" \
    --min-sdk-version 27 --target-sdk-version 27 \
    -o "$OUT/base.apk" --java "$OUT/gen" "$OUT/res.zip"

"$JDK/bin/javac" -encoding UTF-8 -source 8 -target 8 -nowarn \
    -cp "$AJ" -d "$OUT/cls" "$OUT/gen/ru/ogrpfsense/dexpm8/R.java" 2>/dev/null

"$KOTLINC" -cp "$AJ:$KSTD:$OUT/cls" -d "$OUT/kt" \
    $(find "$ROOT/app/src/main/java" -name '*.kt') 2>&1 \
    | grep -Ev 'warning:|deprecat|^$' || true

echo "классов собрано: $(find "$OUT/kt" -name '*.class' | wc -l)"
