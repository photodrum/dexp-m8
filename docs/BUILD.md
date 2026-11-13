# Окружение, сборка, запуск и отладка

> Раздел «Окружение» перенесён из `docs/CONTEXT.md` (2026-11-09).

## Окружение

- Хост `tools`, Debian 12, пользователь **`denisle`** (не root: под root `~` = `/root`,
  и конфиг Cline с токенами недоступен).
- Репозиторий: https://git.ogrpfsense.ru/denisle/dexp-m8 (GitLab 19.5.3 CE, id 87, public).
  Локально: `/home/denisle/projects/dexp-m8`.
- Всё для сборки лежит в `~/.local` (без sudo):

| Что | Путь | Версия |
|---|---|---|
| JDK | `~/.local/jdk` | Temurin 17.0.21 |
| Android SDK | `~/.local/android-sdk` | cmdline-tools 11076708 |
| — platforms | | android-27, android-34 |
| — build-tools | | 34.0.0 |
| — NDK | | 26.3.11579264 (r26d) |
| — CMake | | 3.22.1 |
| — platform-tools | | adb |
| Kotlin | `~/.local/kotlinc` | 1.9.24 |
| Gradle | `~/.local/gradle-8.7` | 8.7 |

- `local.properties`: `sdk.dir=/home/denisle/.local/android-sdk`.
- Penpot: https://penpot.ogrpfsense.ru, MCP «Penpot_Motion», файл **«UI»**, рабочая страница **v5**.
- **Два разных токена GitLab**, не путать:
  - fine-grained у аккаунта `mcp-agent` — только для MCP (`MCP tool: Execute`). `git push` ему
    запрещён: `403 [Code: Push]`.
  - legacy PAT у аккаунта `denisle` — только для git, лежит в
    `~/.config/dexp-m8/git-push-token` (600). Подставляется через `tools/git-askpass.sh`.

## Сборка

Всё окружение в `~/.local`, sudo не нужен. Переменные надо выставлять в каждой сессии:

```bash
export JAVA_HOME=$HOME/.local/jdk
export ANDROID_HOME=$HOME/.local/android-sdk
export PATH=$JAVA_HOME/bin:$PATH
```

| Что | Команда | Результат |
|---|---|---|
| Debug APK | `~/.local/gradle-8.7/bin/gradle -p ~/projects/dexp-m8 assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk` (4,9 МиБ, 5 157 572 б; внутри `libdexp_core.so`, `libdexp_cre.so`, `libdexp_djvu.so`) |
| Release APK | `... assembleRelease` | `app/build/outputs/apk/release/app-release-unsigned.apk` (145 КБ) |
| Быстро, без Gradle | `~/projects/dexp-m8/tools/build.sh` | `build/app-debug.apk` (~0,9 МБ) — **без `.so`**; Kotlin stdlib в dex, подпись как у Gradle (ставится поверх без удаления) |
| Только типы Kotlin | `~/projects/dexp-m8/tools/typecheck.sh` | ~10 сек |
| Тесты C-ядра | см. ниже | 34 проверки |

Первая сборка Gradle ~2 минуты (качает AGP и CMake), повторная ~40–70 сек. С движками (7.1)
первая нативная сборка — ещё 3–4 мин (~600 файлов), потом инкрементально.

**Нативная часть без Gradle** — для подбора флагов движков быстрее (тот же toolchain, что у AGP):

```bash
SDK=$HOME/.local/android-sdk; CM=$SDK/cmake/3.22.1/bin
$CM/cmake -S ~/projects/dexp-m8/app/src/main/cpp -B /tmp/r0-build -G Ninja \
  -DCMAKE_MAKE_PROGRAM=$CM/ninja \
  -DCMAKE_TOOLCHAIN_FILE=$SDK/ndk/26.3.11579264/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=armeabi-v7a -DANDROID_PLATFORM=android-27 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release
$CM/ninja -C /tmp/r0-build -k 0 dexp_cre > /tmp/x.log 2>&1   # или dexp_djvu
```

Проверка `.so`: `llvm-readelf -d` (в `NEEDED` только системные), `llvm-readelf --dyn-syms -W`
(наружу — только `dexp_*_probe` / `Java_*`), размер после `llvm-strip`. `llvm-*` лежат в
`$SDK/ndk/26.3.11579264/toolchains/llvm/prebuilt/linux-x86_64/bin`.

**Таблицы fribidi** (`app/src/main/cpp/engines/cre/fribidi/`) генерирует `tools/gen_fribidi.sh`
хостовым `gcc` (meson не нужен). Результат в git; перезапускать только после обновления подмодуля.

**Заголовки libjpeg-turbo** (`app/src/main/cpp/engines/cre/jpeg/`: `jconfig.h`, `jconfigint.h`,
`jversion.h`) сгенерированы их CMake (только configure, без сборки) и лежат в git. После обновления
подмодуля — перегенерировать и сверить список `CRE_JPEG_NAMES` в `engines/cre.cmake` с `JPEG_SOURCES`
их `CMakeLists.txt`:

```bash
SDK=~/.local/android-sdk; CM=$SDK/cmake/3.22.1/bin
$CM/cmake -S third_party/libjpeg-turbo -B /tmp/ljt-gen -G Ninja -DCMAKE_MAKE_PROGRAM=$CM/ninja \
  -DCMAKE_TOOLCHAIN_FILE=$SDK/ndk/26.3.11579264/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=armeabi-v7a -DANDROID_PLATFORM=android-27 -DWITH_SIMD=0 -DENABLE_SHARED=0 \
  -DWITH_TOOLS=0 -DWITH_TESTS=0 -DWITH_TURBOJPEG=0
cp /tmp/ljt-gen/{jconfig,jconfigint,jversion}.h app/src/main/cpp/engines/cre/jpeg/
```

**Движки читалки (с п. 7)** — git-подмодули в `third_party/` (`shallow = true`):
crengine, djvulibre, xxhash, fribidi, libunibreak, utf8proc, libjpeg-turbo
(тег 3.2.1), freetype (тег `VER-2-14-3`), harfbuzz (тег `13.0.1`) — последние два для crengine, с 7.3а.
MuPDF удалён в 7.3б (решение 2026-11-11: без PDF/CBZ, лицензия).
После клона (или после `git pull`, если добавился подмодуль):

```bash
cd ~/projects/dexp-m8
git submodule update --init --depth 1
```

Если в старом клоне остался `third_party/mupdf` (после `git pull` с 7.3б): `git submodule deinit -f third_party/mupdf; rm -rf third_party/mupdf .git/modules/third_party/mupdf`.

## Тесты C-ядра на хосте

Без NDK и без устройства — обычным `gcc`:

```bash
cd ~/projects/dexp-m8/app/src/main/cpp
gcc -std=gnu99 -O1 -g -Wall -Wextra -Icore -fsanitize=address,undefined \
    -o /tmp/test_core core/book.c test/test_core.c
cd test && setarch -R /tmp/test_core testdata/sample.fb2
```

Ожидаемый вывод: `parse rc=0`, `paras=4 titles=3 poems=2`, `meta rc=0 … complete=1`, `all good`.
`setarch -R` (без ASLR) обязателен: ASan из gcc 12 на ядре хоста (7.0, высокая энтропия mmap)
примерно в 1 запуске из 7 падает до `main` с `AddressSanitizer:DEADLYSIGNAL` — и на старом коде тоже.

## Проверка разбора OPDS на хосте

Ядро сетевых каталогов (`core/Opds.kt`) разбирается на JVM без устройства и без Gradle:
`parse` и `parseTemplate` — чистые функции в `companion object`.

```bash
export JAVA_HOME=$HOME/.local/jdk PATH=$HOME/.local/jdk/bin:$PATH
KX=~/.gradle/caches/modules-2/files-2.1/net.sf.kxml/kxml2/2.3.0/ccbc77a5fd907ef863c29f3596c6f54ffa4e9442/kxml2-2.3.0.jar
# стабы android.content.Context / ConnectivityManager / Base64 / ui.Trace — в /tmp/optest/stub
# companion object в Opds — private, поэтому для теста копия без слова private:
sed 's/    private companion object {/    companion object {/' \
    app/src/main/java/ru/ogrpfsense/dexpm8/core/Opds.kt > /tmp/optest/h/Opds.kt
$HOME/.local/kotlinc/bin/kotlinc -cp $KX -d /tmp/optest/out \
    /tmp/optest/stub/*.kt /tmp/optest/h/Opds.kt /tmp/optest/TplTest.kt
java -Dfile.encoding=UTF-8 -cp "/tmp/optest/out:$KX:$HOME/.local/kotlinc/lib/kotlin-stdlib.jar" TplTestKt
```

Проверяет то, что живёт в разборе, а не в сети: `rel="search"` из фида, OpenSearch-описание
BookLore → абсолютный шаблон, выбор `<Url kind="acquisition">`, шаблон без `{searchTerms}`,
снятие лишних подстановок (`{startIndex?}`, `{language}`), шаблон Calibre. Успех — строки `OK…`
и `=== ВСЁ ОК`; `FAIL` в выводе — падение разбора.

## Проверка шифрования пароля каталога

Пароль OPDS в базе — шифртекст (`core/Pass.kt`, `v1:` + Base64). Проверяется только на книге:
`AndroidKeyStore` на JVM нет. После установки APK и одного запуска:

```bash
adb shell 'run-as ru.ogrpfsense.dexpm8 sqlite3 databases/library.db \
  "pragma user_version; select substr(pass,1,16) from catalogs"'
# ожидается: 7   и   v1:BUt/ZJpRRCRbPrY

adb shell 'run-as ru.ogrpfsense.dexpm8 sh -c \
  "grep -c <старый-пароль> databases/library.db databases/library.db-wal"'
# ожидается: 0 в обоих файлах — иначе уборка (VACUUM) не прошла
```

В трассе (`files/DexpM8-trace.txt`) должны быть строки «pass: перешифровано N паролей каталогов»,
«db v7: открыта, версия 7» и «db v7: уборка после перешифрования готова». Вход в каталог после
обновления должен работать без повторного ввода пароля — это и есть проверка, что ключ прочитан.

## Установка на книгу

```bash
$ANDROID_HOME/platform-tools/adb devices          # книга должна быть в списке
$ANDROID_HOME/platform-tools/adb install -r -d \
  ~/projects/dexp-m8/app/build/outputs/apk/debug/app-debug.apk
```

`-r` — переустановка с сохранением данных, `-d` — разрешает откат на более старую версию.

## adb на Windows (PowerShell)

Папка `C:\platform-tools` не в `PATH`, а PowerShell не запускает программы из текущей папки
без пути: пиши `.\adb`, а не `adb`. Ниже команды даны как `adb` — на Windows подставляй `.\adb`.

## Полезные команды на устройстве

```bash
adb shell getprop ro.sf.lcd_density     # ожидаем 320 → density 2.0
adb shell getprop ro.product.cpu.abi    # ожидаем armeabi-v7a (прошивка 32-битная)
adb shell wm size                       # ожидаем 1404x1872
adb logcat -s DexpM8:V AndroidRuntime:E # логи и падения
adb shell dumpsys meminfo ru.ogrpfsense.dexpm8   # PSS — главная метрика на 1 ГБ
# R0 (только debug): открыть файл на отладочном экране движков, минуя базу (EinkActivity.probeFromIntent)
adb shell am start -W -n ru.ogrpfsense.dexpm8/.ui.EinkActivity --es probe "'/sdcard/Books/r0/r0-cover.fb2'"
adb logcat -s DexpM8:V | grep probe     # «probe: <движок> rc=0 <мс>»
# замеры crengine с листанием (30 стр.): строка «bench: …» в logcat; выключить — rm
adb shell touch /data/local/tmp/dexp-bench   # adb shell rm /data/local/tmp/dexp-bench
```

**Служба «DEXP M8 — выключение» (клавиша «Выключение» на шторке B5, п. 8.1).** Экрана «Спец. возможности»
в прошивке книги нет (`cmd package resolve-activity -a android.settings.ACCESSIBILITY_SETTINGS` → `No activity found`),
поэтому службу включают один раз через adb. Переживает перезагрузку; слетает при сбросе книги и при удалении оболочки
(переустановка `install -r` её не снимает). Без службы клавиша пишет «Нужна настройка».

```bash
adb shell settings put secure enabled_accessibility_services ru.ogrpfsense.dexpm8/ru.ogrpfsense.dexpm8.core.PowerService
adb shell settings put secure accessibility_enabled 1
adb shell dumpsys accessibility | grep -o 'services:{[^}]*}'   # ожидаем «DEXP M8 — выключение»
# выключить: adb shell settings delete secure enabled_accessibility_services; adb shell settings put secure accessibility_enabled 0
```

Команда затирает список включённых служб: если на книге есть другие (по умолчанию нет, `settings get` → `null`) —
дописать через `:`.

В скриптах с циклом `while read` по файлам каждому `adb shell`/`adb exec-out` давать `< /dev/null`:
иначе adb съедает stdin, и цикл заканчивается после первого файла.

**Проверка экранов, которым нужно или не нужно право на память (B46, B45, пустые состояния).**

```bash
adb shell pm revoke ru.ogrpfsense.dexpm8 android.permission.READ_EXTERNAL_STORAGE  # «права нет»
adb shell pm grant  ru.ogrpfsense.dexpm8 android.permission.READ_EXTERNAL_STORAGE  # вернуть
adb exec-out screencap -p > /tmp/shot.png     # пустой/нет файла — повторить, иногда не с первого раза
```

При холодном старте без права приложение само открывает системный диалог, и он перекрывает экран:
сначала нажать «ОТКЛОНИТЬ», потом идти в библиотеку, иначе `pm revoke` бесполезен (право уже выдано).
Чтобы увидеть B45 на PDF, положить `.pdf` в `Books` — скан подхватит его первым в «Новые».

## Git и доступы

Remote — HTTPS: `https://git.ogrpfsense.ru/denisle/dexp-m8.git`.
SSH не работает: порт 22 на `git.ogrpfsense.ru` обслуживает NPM-хост (Debian 12),
а не GitLab VM (Debian 13), поэтому ключ до GitLab не доходит. Порты 2222 и 443 закрыты.

Токен для git — legacy PAT аккаунта `denisle`, файл `~/.config/dexp-m8/git-push-token`
(права 600). Подставляется через `tools/git-askpass.sh`, в `git config` и в командную
строку не попадает:

```bash
cd ~/projects/dexp-m8
GIT_ASKPASS=$PWD/tools/git-askpass.sh GIT_TERMINAL_PROMPT=0 git push origin main
```

Токен MCP (fine-grained у `mcp-agent`) для git **не годится** — ему запрещён push
(`403 [Code: Push]`), он только для инструментов MCP.

Проверка, под кем авторизован git:

```bash
GIT_ASKPASS=$PWD/tools/git-askpass.sh GIT_TERMINAL_PROMPT=0 \
  git -c credential.helper= ls-remote origin
```

## Частые ошибки

| Симптом | Причина |
|---|---|
| `adb devices` → `offline`, потом книга пропадает; `logcat` сам завершается через 1–2 мин | обрыв USB. `adb kill-server` не помог, помогло выдернуть кабель, подождать 5 с и вставить в порт на задней панели ПК; на книге — «Передача файлов (MTP)» и «Разрешить отладку» |
| `adb` не распознан в PowerShell | запускать как `.\adb` из `C:\platform-tools` |
| `java: command not found` (`apksigner`, `d8`, `kotlinc` в `typecheck.sh`, фон) | в PATH нет JDK: `export JAVA_HOME=$HOME/.local/jdk PATH=$HOME/.local/jdk/bin:$PATH` |
| `adb: command not found` в Linux | `export PATH=$HOME/.local/android-sdk/platform-tools:$PATH` |
| Книга не видна в `adb devices` после переподключения или перезагрузки (LXC `tools`, VMID 113, USB проброшен: udev `51-android.rules`, vendor `2207`) | `pkill adb; adb start-server`, подождать 3 с. Нет `2207` в `/sys/bus/usb/devices` (`lsusb \| grep 2207`, `2207:0011`) — другой порт ПК |
| `401` на `/api/v4/mcp` | legacy-токен вместо fine-grained, либо `PRIVATE-TOKEN` вместо `Authorization: Bearer` |
| `403 insufficient_granular_scope` | у токена нет нужного ресурса — дописать в Edit PAT |
| `403 [Code: Push]` | у токена нет `write_repository` |
| `Permission denied (publickey,password)` | SSH уходит в NPM, а не в GitLab — использовать HTTPS |
| Gradle: `'-' is not a valid file-based resource name` | в `res/` только `a-z0-9_` |
| `UnsatisfiedLinkError` в release | R8 выкинул `NativeBook` — нужен `-keep class ... { *; }` |
| `aapt2: <manifest> must have a 'package' attribute` | AGP 8 вырезает `package`; в `build.sh` подставляем `sed` |
| antiword: `Exactly one of the DEBUG and NDEBUG flags MUST be set` | Gradle Debug не задаёт `NDEBUG`; в `engines/*.cmake` у библиотек движков `-DNDEBUG` всегда |
| crengine `lvimg.cpp`: `undeclared identifier 'JCS_EXT_BGRX'` | нужен libjpeg-turbo, а не IJG. Решено в 7.1а: цель `cre_jpeg` (libjpeg-turbo 3.2.1); если ошибка вернулась — в `CRE_INC` перед `jpeglib.h` IJG стоит другой каталог |
| ld: `undefined symbol: png_init_filter_functions_neon` | libpng 1.6 на ARM ждёт `arm/*.c`; задано `PNG_ARM_NEON_OPT=0` |
| Терминал Cline зависает на длинном heredoc | писать скрипт файлом (редактором), затем запускать |
| В терминале Cline нет вывода долгих команд (`typecheck.sh`, Gradle) | `cmd > /tmp/x.log 2>&1`, затем читать файл |
| `screencap` отдаёт чёрный кадр | экран погас по таймауту: `adb shell input keyevent KEYCODE_WAKEUP`; сразу после запуска E-Ink ещё не обновилась — снимать через 10–15 с |
| Иконка не рисуется (пустая круглая клавиша), ошибок в логе нет | `icons.draw(c, "sort@36.8x30.6", …)` — такого варианта в `IconSpecs` нет, а `Icons.draw` на неизвестном имени молча выходит. Сверить имя: `grep -n '"sort' ui/IconSpecs.kt`; варианты с `@ШxВ` появляются, только если размер снят в Penpot, а `plus~1201ai8` и другие варианты B4 в `SKIP` генератора `tools/icon_specs.py` (2026-11-12, 9.6) |
| В файле после записи через `sed`/`cat` из терминала VS Code появились `ESC]633…` | сборка падает на первой строке; писать файлы редактором, проверять `grep -c $'\x1b' файл` = 0 |
| Скрипт с `while`/`grep -q` и `adb logcat -d` подвисает в терминале Cline | запускать по одному файлу в фоне, читать лог отдельно |
| Проба листает 30 страниц и «тормозит» | на книге остался `/data/local/tmp/dexp-bench`: `adb shell rm /data/local/tmp/dexp-bench` |
| `tools/typecheck.sh`: `unresolved reference: BuildConfig` | `BuildConfig` генерирует только Gradle; в `typecheck.sh` и `build.sh` есть заглушка (`DEBUG = true`) с 2026-11-12 |
| `tools/typecheck.sh` / `tools/build.sh`: `OutOfMemoryError` в psi2ir или на `ShelfScreen.kt` | **решено 2026-11-13:** оба скрипта сами ставят `JAVA_OPTS=-Xmx1600m` (если переменная не задана); нужен только `JAVA_HOME` и PATH JDK. Раньше требовалось передавать вручную |
| `tools/typecheck.sh` без JDK в PATH: пишет «классов собрано: 0» и **выходит с кодом 0** | `kotlinc` не нашёл `java` и умер молча — без ошибки в логе. Всегда `export JAVA_HOME=$HOME/.local/jdk PATH=$HOME/.local/jdk/bin:$PATH`; признак успеха — «классов собрано: 135» и выше |
| JVM-харнесс ядра OPDS: `cannot access 'Companion': it is private in 'Opds'` | `companion object` в `Opds` — `private` (с 10.6); для теста собирать копию без слова `private` и вызывать `searchUrl` через экземпляр (`docs/BUILD.md`, «Проверка разбора OPDS на хосте») |
| `db v7: VACUUM FAIL: cannot VACUUM from within a transaction` | `VACUUM` нельзя внутри `onUpgrade` (там уже открыта транзакция). Уборка после перешифрования паролей — в `LibraryDb.scrub()`, вызывается из `LibraryDb.open()` после открытия базы, не из `onUpgrade` (10.12) |
| Gradle, запущенный фоном (`nohup … &`), падает с `Bad file descriptor` / зависает с пустым логом | терминал Cline закрывает stdin фонового процесса: `setsid ./gradlew assembleDebug --offline </dev/null > /tmp/build.log 2>&1 &`, затем читать `/tmp/build.log` |
| `assembleDebug` пишет `UP-TO-DATE`, а APK старый в глазах «ничего не собралось» | APK мог быть собран раньше той же сессией: сверить `stat -c '%y %n'` у `app-debug.apk` и правленого `.kt` — APK должен быть новее |
| `gradle` / `gradlew` «не найден» | Gradle в PATH не стоит: `~/.local/gradle-8.7/bin/gradle` (или `export PATH=$HOME/.local/gradle-8.7/bin:$PATH`); `gradlew` работает, но тянет дистрибутив в `~/.gradle` |
| Два вызова терминала **параллельно** (два `run_commands` в одном ответе): вывод перемешивается, «command completion could not be observed», команды попадают в чужую строку | терминал один на чат. Несколько команд — одним вызовом массивом (внутри вызов они идут последовательно) |
| «Быстрый» (B5/B37): шторка или новый экран рисуются кашей поверх старого | A2-режим на большой кадр с серым; такие кадры должны идти через `EinkActivity.bigFrame` / `fullOnSwitch` (п. 8.2) |
| `NoClassDefFoundError: kotlin.jvm.internal.Lambda` | APK без Kotlin stdlib (старый `build.sh`); исправлено 2026-11-11 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | другой ключ подписи; `build.sh` и Gradle используют один `~/.android/debug.keystore`. Иначе `adb uninstall` (данные пропадут) |
| `run-as` не работает | APK не debuggable; значения проверять через `settings get`/`getprop` |
| `git ls-remote` на `github.com/koreader/djvulibre` висит | брать с `gitlab.com/koreader/djvulibre` |
| `penpot_dump.js` не запустить: в чате Cline нет инструментов MCP Penpot, хотя сервер включён | инструменты MCP подключаются при старте чата: включить сервер в Cline → MCP Servers, затем открыть **новый** чат |
| Ответ `penpot_dump.js` в чате обрезан (`...[truncated N chars]...`) | целиком он лежит в `~/.cline/data/sessions/<id>/<id>.messages.json`: результат положить в `storage`, отдавать частями с меткой (`{MARK, files}`), достать из журнала по метке и сверить длину и djb2 (UTF-16) с Penpot до записи (DEVLOG 2026-11-12, 9.0) |
| Проба пишет «fb2': движка нет», в пути видны `'…'` | кавычки двух уровней: путь передавать как `--es probe "'$f'"` (один уровень одинарных внутри двойных) |
