#!/usr/bin/env bash
# Пять минимальных тестовых APK для проверки страниц B6 (в книге 7 приложений при PER_PAGE = 12).
#
#   tools/test_apps.sh            собрать в /tmp/testapps (без сети и Gradle, ~10 с)
#   tools/test_apps.sh --install  собрать и поставить на книгу
#   tools/test_apps.sh --clean    снять тестовые пакеты с книги
#
# Пакеты ru.dexpm8.test.app1…5 с подписью tools/debug.keystore (ключ `dexp`), подпись на книге
# не совпадает с оболочкой — ставить только для проверки и снимать после (`--clean`).
# После установки и удаления: `am force-stop ru.ogrpfsense.dexpm8`, иначе список не перечитается,
# и заново включить службу питания (`docs/BUILD.md`, «Служба „DEXP M8 — выключение“»).
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/.local/android-sdk}"
BT="$SDK/build-tools/34.0.0"
AJ="$SDK/platforms/android-34/android.jar"
JDK="${JAVA_HOME:-$HOME/.local/jdk}"
ADB="$SDK/platform-tools/adb"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KS="$ROOT/tools/debug.keystore"
OUT=/tmp/testapps
COUNT=5

clean() {
    for i in $(seq 1 $COUNT); do
        "$ADB" uninstall "ru.dexpm8.test.app$i" || true
    done
    echo "==> тестовые пакеты сняты"
}

if [ "${1:-}" = "--clean" ]; then
    clean
    exit 0
fi

mkdir -p "$OUT/src/test" "$OUT/cls" "$OUT/dex"

cat > "$OUT/src/test/MainActivity.java" <<'EOF'
package test;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView t = new TextView(this);
        t.setText("Тестовое приложение DEXP M8");
        t.setTextSize(32f);
        setContentView(t);
    }
}
EOF

"$JDK/bin/javac" -encoding UTF-8 -source 8 -target 8 -nowarn -cp "$AJ" \
    -d "$OUT/cls" "$OUT/src/test/MainActivity.java"

PATH="$JDK/bin:$PATH" "$BT/d8" --release --min-api 21 --lib "$AJ" --output "$OUT/dex" \
    $(find "$OUT/cls" -name '*.class')

for i in $(seq 1 $COUNT); do
    PKG="ru.dexpm8.test.app$i"
    D="$OUT/a$i"
    mkdir -p "$D"
    cat > "$D/AndroidManifest.xml" <<EOF
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$PKG">
    <application android:label="Тест $i">
        <activity android:name="test.MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>
    </application>
</manifest>
EOF
    "$BT/aapt2" link -I "$AJ" \
        --manifest "$D/AndroidManifest.xml" \
        --min-sdk-version 21 --target-sdk-version 27 \
        --version-code 1 --version-name "1.$i" \
        -o "$D/base.apk"
    cp "$OUT/dex/classes.dex" "$D/classes.dex"
    (cd "$D" && zip -q -u base.apk classes.dex)
    "$BT/zipalign" -f -p 4 "$D/base.apk" "$D/aligned.apk"
    "$BT/apksigner" sign --ks "$KS" --ks-pass pass:dexpdebug --key-pass pass:dexpdebug \
        --ks-key-alias dexp --out "$D/test$i.apk" "$D/aligned.apk"
    echo "==> $D/test$i.apk · «Тест $i» · $PKG"
    if [ "${1:-}" = "--install" ]; then
        "$ADB" install -r -d "$D/test$i.apk"
    fi
done

if [ "${1:-}" = "--install" ]; then
    "$ADB" shell am force-stop ru.ogrpfsense.dexpm8
    echo "==> поставлено; оболочка остановлена — включить службу питания и запустить:"
    echo "    adb shell settings put --user 0 secure enabled_accessibility_services ru.ogrpfsense.dexpm8/ru.ogrpfsense.dexpm8.core.PowerService"
    echo "    adb shell settings put --user 0 secure accessibility_enabled 1"
    echo "    adb shell am start -n ru.ogrpfsense.dexpm8/.ui.EinkActivity"
fi
