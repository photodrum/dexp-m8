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
| Debug APK | `~/.local/gradle-8.7/bin/gradle -p ~/projects/dexp-m8 assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk` (5,8 МиБ, 5 815 104 б; внутри `libdexp_core.so`, `libdexp_cre.so`, `libdexp_djvu.so`; +73 КБ шрифтов PT Serif) |
| Release APK | `... assembleRelease` | `app/build/outputs/apk/release/app-release.apk` (4,3 МиБ, 4 478 840 б на 2026-11-21, подписан ключом `dexp-m8`; внутри те же три `.so`). `lintVitalRelease` печатает `ExpiredTargetSdkVersion` (targetSdk 27) — это не ошибка сборки (`BUILD SUCCESSFUL`), игнорировать |
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

**Правки движков поверх подмодулей** — патчами в `tools/patches/`, применяются при конфигурации
CMake (`engines/cre.cmake`): `git apply` идемпотентен, уже наложенный патч пропускается.
Правка, сделанная прямо в подмодуле, потеряется при `git submodule update` — сохранять патчем.
Патч — единственная копия правки, коммитить в подмодуль нечего (он чужой).

Два условия, без которых патч тихо перестаёт работать (сделано 2026-11-19, замечание 19):

- файл, который правит патч, объявлен зависимостью конфигурации
  (`CMAKE_CONFIGURE_DEPENDS`), иначе откат подмодуля при живом кеше `app/.cxx` не перезапускает
  CMake и натив собирается непропатченным. Проверка: откатить файл в подмодуле, `assembleDebug`,
  в файле должны снова появиться строки патча;
- `skip-worktree` на файле подмодуля **не ставить**: он прячет файл и от `git apply`
  (`pathspec did not match any file(s) known to git`) — патч не наложится. Грязь подмодуля
  в `git status` родителя убирается опцией `ignore = dirty` в `.gitmodules`.

| Патч | Что и зачем |
|---|---|
| `crengine-19-glyph-align.patch` | `LVFontGlyphCacheItem::newItem` берёт память `posix_memalign` (Z19): `alignas(16)` на `bmp` заставлял clang писать NEON-инструкцией по 16 байт, а bionic `malloc` даёт 8 — SIGBUS на кегле 8…12 |

## Проверки на хосте

Тесты C-ядра, разбор OPDS, срезы авторов и серий, шифрование пароля каталога —
[`docs/CHECKS.md`](CHECKS.md) (вынесено отсюда 2026-11-18: при сборке не нужны).

## Подпись APK (п. 20)

Свой ключ, **один на debug и release** — `signingConfigs` в `app/build.gradle.kts` читает
`~/.dexp-m8-keys/release.properties`:

```properties
storeFile=/home/denisle/.dexp-m8-keys/release.jks
storePassword=< случайные 48 hex >
keyAlias=dexp-m8
keyPassword=< те же 48 hex >
```

Каталог `~/.dexp-m8-keys` — права 700, файлы 600, в `.gitignore` (`*.jks`, `*.keystore`).
Отпечаток сертификата (`SHA-256`): `9E:DF:F2:D1:58:8A:20:A9:FE:13:7F:05:69:74:1D:21:A3:6C:9F:87:30:4D:10:9F:A2:A0:48:16:A7:73:17:FC`,
alias `dexp-m8`, `CN=DEXP M8, O=ogrpfsense, C=RU`, срок до 2054. `tools/build.sh` берёт тот же файл.

**Почему один ключ на оба типа сборки:** Android пускает APK поверх установленного только при
совпадающей подписи. Пока release был неподписан, а debug — ключом `~/.android/debug.keystore`,
переход на свой ключ требовал `adb uninstall` и терял базу, настройки и ключ `AndroidKeyStore`
(пароли каталогов). Теперь `assembleDebug` и `assembleRelease` подписываются одинаково и ставятся
друг поверх друга без потери данных — проверено 2026-11-18 (debug → release → debug).

**Ключ нельзя терять:** с другим ключом обновление не встанет
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) и данные придётся снести. Копия — минимум в двух местах
вне этой машины (у п. 20 копия ещё не сделана — сделать перед первой публикацией).

Нет файла `release.properties` — release собирается неподписанным
(`app-release-unsigned.apk`), сборка не падает; подписывать им нельзя, книга обновление не поставит.
Проверка: `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`.

## Публикация версии (проверка обновлений на B43)

Кнопка «Проверить обновления» на B43 читает манифест `update.json` из корня ветки `main`:
сначала со своего GitLab (`https://git.ogrpfsense.ru/denisle/dexp-m8/-/raw/main/update.json`),
при недоступности — с GitHub-зеркала (`https://raw.githubusercontent.com/photodrum/dexp-m8/main/update.json`).
Оба адреса публичные, токен в APK не нужен (`core/UpdateCheck.kt`).

Выпуск версии — правка `update.json` и обычный коммит в `main` (GitHub-зеркало подхватит само):

```json
{ "versionCode": 2, "versionName": "0.1.1",
  "url": "https://…/app-release.apk", "sha256": "<sha256 APK>", "notes": "что нового" }
```

```bash
sha256sum app/build/outputs/apk/release/app-release.apk      # в "sha256"
python3 tools/gl_publish_apk.py app/build/outputs/apk/release/app-release.apk 1.0.0   # APK в GitLab Packages
```

Скрипт сам печатает `sha256`, `size` и готовый кусок `update.json` — вписывать их руками
по `sha256sum` не нужно. **Токену нужен scope `api`:** git push хватает `write_repository`,
а `/projects/:id/packages/generic/…` с ним отвечает `403 insufficient_scope` (2026-11-18).
Токен — «Настройки → Токены доступа» в GitLab, галочка `api`, файл
`~/.config/dexp-m8/git-push-token`. Прямая ссылка на файл открывается **без токена**
(проект публичный) — именно поэтому B76 может качать APK.

**Релиз на GitHub** (зеркало `photodrum/dexp-m8`; само зеркало релизы и теги не создаёт) —
последним шагом, после `git push` релизного коммита:

```bash
python3 tools/gh_publish_release.py app/build/outputs/apk/release/app-release.apk 1.4.3 --dry-run  # проверка, токен не нужен
python3 tools/gh_publish_release.py app/build/outputs/apk/release/app-release.apk 1.4.3
```

Скрипт ждёт (до 20 мин, `--wait=N`), пока коммит доедет до GitHub, создаёт релиз `v<версия>` с
текстом из `update.json` и файлом `dexp-m8-<версия>.apk`, скачивает его обратно и сверяет
`sha256`. Повторный запуск обновляет релиз и заменяет файл. **Нужен токен GitHub:** fine-grained
PAT аккаунта `photodrum`, репозиторий `dexp-m8`, право `Contents: Read and write`, файл
`~/.config/dexp-m8/github-token` (права 600) или `GITHUB_TOKEN`. `url` в `update.json` остаётся на
GitLab Packages — книга качает оттуда.

`versionCode` обязан расти монотонно — сравнение идёт по нему, а не по `versionName`.
Ответ 404 на обоих хостах B43 показывает как «версия ещё не опубликована», код 5xx и обрыв — «сервер не ответил».
Проверить манифест без книги: `curl -s https://git.ogrpfsense.ru/denisle/dexp-m8/-/raw/main/update.json`.

**Установка обновления (B76, п. 13).** Экран открывается строкой «Обновления» на B43; качает APK,
сверяет `sha256` с манифестом и отдаёт `PackageInstaller`. Требования к публикации:

- `sha256` — настоящая сумма release-APK. **Сумма из 64 нулей считается отсутствующей**: B76
  ответит «в манифесте нет суммы» и ничего не скачает.
- `url` — прямая ссылка на файл (GitLab Packages отдаёт редирект — он разрешается). Страница
  релизов `/-/releases` файлом не является и не скачается.
- `size` (необязательно) — размер в байтах: до скачивания показывается в подстроке, а проверка
  места на книге идёт по `Content-Length` либо по нему.
- APK обязан быть подписан тем же ключом, что установлен на книге — иначе
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (см. «Смена подписи»).

Проверить цепочку без публикации (debug-сборка): собрать release, раздать каталог локально
`python3 -m http.server 8137 --bind 0.0.0.0` (порт 8000 на машине бывает занят), положить рядом
`update.json` с `versionCode` выше установленного и настоящей суммой, затем на книге:

```bash
adb shell am start -n ru.ogrpfsense.dexpm8/.ui.EinkActivity \
  --es screen updates --es update http://<ip>:8137/update.json
```

`--es update` подменяет список источников и работает только в debug. Установка проходит через
системный диалог («Установить обновление этого приложения?»), после неё Android **убивает процесс**
и запускает оболочку заново — экран «Готово» увидеть нельзя, это норма.

## Смена подписи без потери данных

Понадобится, если ключ всё же меняется (новый `release.jks`, потерянный файл) или при первом
переходе с отладочного ключа. `run-as` работает только на **debuggable** сборке, поэтому порядок
такой: снять данные → поставить debug с новым ключом → залить данные → поставить release поверх.

```bash
ADB=~/.local/android-sdk/platform-tools/adb; P=ru.ogrpfsense.dexpm8
B=~/dexpm8-backup-$(date +%F); mkdir -p $B/databases $B/shared_prefs
# 1. снять данные (на живой базе копия приходит с нулями — сначала остановить)
$ADB shell am force-stop $P; sleep 1
for f in library.db library.db-wal library.db-shm; do
    $ADB exec-out run-as $P cat databases/$f > $B/databases/$f; done
$ADB exec-out run-as $P cat shared_prefs/shell.xml > $B/shared_prefs/shell.xml
python3 -c "import sqlite3;c=sqlite3.connect('$B/databases/library.db');print(c.execute('pragma integrity_check').fetchone(),c.execute('select count(*) from books').fetchone())"
# 2. снять админа (см. грабли) и удалить пакет
$ADB uninstall $P
# 3. поставить debug с новым ключом, один раз запустить (создаст каталоги), остановить
$ADB install app/build/outputs/apk/debug/app-debug.apk
$ADB shell am start -W -n $P/.ui.EinkActivity; sleep 6; $ADB shell am force-stop $P
# 4. залить данные (абсолютный путь обязателен) и выдать права заново
$ADB shell run-as $P rm -f databases/library.db-wal databases/library.db-shm databases/library.db-journal
$ADB shell "run-as $P sh -c 'cat > /data/data/$P/databases/library.db'" < $B/databases/library.db
$ADB shell "run-as $P sh -c 'cat > /data/data/$P/shared_prefs/shell.xml'" < $B/shared_prefs/shell.xml
$ADB shell run-as $P chmod 660 shared_prefs/shell.xml
$ADB shell pm grant $P android.permission.READ_EXTERNAL_STORAGE
$ADB shell pm grant $P android.permission.WRITE_EXTERNAL_STORAGE
# 5. release поверх debug — данные остаются, подпись та же
$ADB install -r app/build/outputs/apk/release/app-release.apk
```

Проверка после: `run-as … sha256sum` совпадает с копией (база может отличаться, если приложение
уже писало — тогда сверять число книг и `pos`); на B1 «Сейчас читаю» с тем же процентом.

Что восстановление **не** вернёт: ключ `AndroidKeyStore` (`dexpm8.catalog.pass.v1`) — он жил в
отдельном хранилище пакета и удалён вместе с ним, поэтому пароли каталогов OPDS придётся ввести
заново (`catalogs.pass` станет нерасшифровываемым). Кэш движка `cache/cre` перестроится сам.
Книги на `/sdcard` не затрагиваются — `uninstall` внешнее хранилище не трогает.

## Установка на книгу

```bash
$ANDROID_HOME/platform-tools/adb devices          # книга должна быть в списке
$ANDROID_HOME/platform-tools/adb install -r -d \
  ~/projects/dexp-m8/app/build/outputs/apk/debug/app-debug.apk
```

`-r` — переустановка с сохранением данных, `-d` — разрешает откат на более старую версию.

**Грабля (2026-11-26, релиз 1.4.1): откат `install -d` и обновление «вперёд» ломали базу.** Откат на
старый APK (`-d`) оставляет в `library.db` новые колонки (`annotation` и др.), а `SQLiteOpenHelper`
после пустого `onDowngrade` всё равно записывает в `user_version` старое число. Следующее обновление
шло по миграциям заново и падало на `ALTER TABLE … ADD COLUMN annotation` («duplicate column name»):
`LibraryDb` не открывался, на главном не работала ни одна кнопка с данными (Библиотека, Аудиокниги),
в `logcat` — `DEXPM8_CRASH … onUpgrade`. Исправлено в 1.4.2: `LibraryDb.addColumn` проверяет
`PRAGMA table_info` перед `ADD COLUMN`. **Новую миграцию с `ADD COLUMN` писать только через
`addColumn`.** Если книга уже в таком состоянии — поставить 1.4.2 или новее поверх (`install -r`),
данные целы.

**SDK здесь — `~/.local/android-sdk`** (`local.properties`), в `PATH` `adb` нет: писать
`~/.local/android-sdk/platform-tools/adb`. **Грабля (2026-11-18):** при подключённой книге
`adb devices` отдаёт пустой список — помогает перезапуск сервера, после него устройство
`6IVJ8C68AP` появляется:

```bash
adb kill-server; adb start-server; adb devices
```

Для снимков экрана без сети — `adb shell svc wifi disable` / `enable` (проверка «нет сети»),
плюс `adb shell input keyevent KEYCODE_WAKEUP` перед `screencap` (иначе чёрный кадр).

**Грабли проверки на книге (2026-11-18, автопроверка обновлений):**

- `adb shell pm clear ru.ogrpfsense.dexpm8` сносит **все** данные: базу, `shared_prefs/shell.xml`,
  шрифты. Для проверки первого запуска это не нужно — хватает `--es screen first`. Восстановление —
  «Смена подписи без потери данных» выше (шаги 3–4), бэкап `~/dexpm8-backup-<дата>`.
- **Экраны, до которых не дойти кликами, открываются отладочным путём** (debug-сборка):
  ```bash
  adb shell am start -n ru.ogrpfsense.dexpm8/.ui.EinkActivity --es screen read   # B39 «Чтение»
  adb shell am start -n ru.ogrpfsense.dexpm8/.ui.EinkActivity --es screen keys   # B13 → «Кнопки и жесты»
  adb shell am start -n ru.ogrpfsense.dexpm8/.ui.EinkActivity --es screen stats  # B32 «Статистика»
  ```
  Причина: на пути к B39/B13 два уровня списка, мастер — только на первом запуске.
- Править `shared_prefs/shell.xml` из `adb shell` нельзя (`Permission denied`): владелец — только
  `run-as`. Порядок: `adb exec-out run-as <пакет> cat shared_prefs/shell.xml > /tmp/shell.xml`,
  правка дома, `adb shell "run-as <пакет> sh -c 'cat > /data/data/<пакет>/shared_prefs/shell.xml'" < /tmp/shell.xml`.
  На живом процессе файл перезапишется из памяти — сначала `am force-stop`.

**Грабли проверки касаниями (2026-11-21, B12):** `adb shell screencap` отдаёт кадр 1404×1872 —
это ровно дизайн-px, масштаба нет. `adb shell input tap` берёт те же координаты без пересчёта:
`input tap 250 1120` — кнопка «Оглавление» на B12. Любой «пересчёт» (например `×0.8`) уводит на
соседний элемент и даёт ложный «код не работает».

**Грабли проверки заряда (2026-11-18, B20):**

- `adb shell dumpsys battery set level N` **не меняет** `status`: при `status: 5` (`FULL`) оболочка
  считает книгу заряжающейся и диалог низкого заряда не покажет. Нужна вся тройка:
  ```bash
  adb shell dumpsys battery set ac 0
  adb shell dumpsys battery set usb 0
  adb shell dumpsys battery unplug
  adb shell dumpsys battery set status 3    # разряжается
  adb shell dumpsys battery set level 14
  ```
  Вернуть как было — `adb shell dumpsys battery reset` (плюс `settings put system screen_brightness`
  и `svc wifi enable`, если опыт шёл через экономию: она их меняет).
- Экономия B20 меняет **системные** яркость и Wi-Fi. После опыта проверить
  `adb shell settings get system screen_brightness` и `settings get global wifi_on`, а `saver_applied`
  в `shell.xml` должен быть `false` — иначе следующая проверка начнётся с чужого состояния.
- Правка `shell.xml` в `adb shell "run-as … sh -c 'sed -i …'"` **ломается на кавычках**
  (`sed: no pattern`, файл не найден). Работает путь `run-as … cat > /tmp/shell.xml` → правка
  на хосте → `adb push /tmp/shell.xml /data/local/tmp/` → `run-as … cp /data/local/tmp/shell.xml
  shared_prefs/shell.xml` (и `am force-stop` заранее).

**Проверка страниц B6 (нужно > 12 приложений, 2026-11-20).** На книге 7 приложений + синтетическая
плитка «Файлы» → одна страница при `PER_PAGE = 12`. Пять минимальных APK собираются без сети и Gradle за
~10 секунд и ставятся на книгу:

```bash
tools/test_apps.sh            # /tmp/testapps/aN/testN.apk, подпись tools/debug.keystore
tools/test_apps.sh --install  # поставить, оболочка будет остановлена
tools/test_apps.sh --clean    # снять после проверки
```

После установки и после `--clean`: запустить оболочку и заново включить службу питания (раздел выше).
Было 12 приложений + «Файлы» = 13 плиток → две страницы, желоб из двух сегментов.

**Проверка экранов по тапам (2026-11-20).** `adb shell input tap X Y` — в **пикселях экрана**
(1404×1872), а зоны касания в коде заданы в дизайн-px: при density 2.0 они совпадают, но зоны
у кнопок узкие. Промах выглядит как «кнопка не работает»:

- «Назад» в шапке на всех экранах — x 41.1…148, y ≈ 112…225 (то есть тап `92 170`). Тап в 410 по x
  попадает в заголовок и ничего не делает;
- на B42 «Очистить» — x 1040…1334, y 1024.5…1146.5 (`SetStorageScreen.onUp`);
- на B6 «…» плитки — правый верхний угол 100×100 от угла плитки (`DOTS_HIT`).

Каждый тап сверять с `onUp` нужного экрана, а не с положением подписи на снимке. `screencap`
отдаёт кадр не сразу: между тапами держать 3–5 с, иначе следующий тап приходит на прежний экран.

**Проверка «Удалить» на B12 без потери данных.** Ставить одноразовый файл, а не удалять книгу
из библиотеки: положить `/tmp/zz-test-delete.fb2` в `/sdcard/Books/`, дождаться скана
(B2 → «Новые» — свежий файл первый), открыть B12 → «Удалить» → «Удалить», затем проверить
`adb shell ls /sdcard/Books/zz-test-delete.fb2` (`No such file`) и число книг в подзаголовке B2.


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
# чтение вслух (R6): движок, локаль, состояние звука; строки «tts: …» в трассе
adb shell settings get secure tts_default_synth              # com.google.android.tts
adb shell pm list packages | grep -i tts                     # + com.svox.pico (без русского)
adb shell dumpsys audio | grep -A2 STREAM_TTS                # state:started — голос идёт
# R0 (только debug): открыть файл на отладочном экране движков, минуя базу (EinkActivity.probeFromIntent)
adb shell am start -W -n ru.ogrpfsense.dexpm8/.ui.EinkActivity --es probe "'/sdcard/Books/r0/r0-cover.fb2'"
adb logcat -s DexpM8:V | grep probe     # «probe: <движок> rc=0 <мс>»
# замеры crengine с листанием (30 стр.): строка «bench: …» в logcat; выключить — rm
adb shell touch /data/local/tmp/dexp-bench   # adb shell rm /data/local/tmp/dexp-bench
# самотест движка (R1–R3.4): открытие, кегль, оглавление, типографика B4; строки «selftest: …»
adb shell am start -n ru.ogrpfsense.dexpm8/.ui.EinkActivity --es reader "'/sdcard/Books/r0/r0-big5.fb2'"
```

**Шрифты читалки (B4/B70).** В APK четыре гарнитуры: Literata и Manrope — срезы из вариативных
файлов, PT Serif — статические файлы из `ofl/ptserif` (B70, 2026-11-15), «Моно» — системный
`Droid Sans Mono`. Исходники `.ttf` в git не лежат (`.gitignore`), их кладут в `tools/fonts/`
и режут через `python3 tools/make_fonts.py`; набор символов сверяется с `literata_400.ttf`
(у PT Serif допущены `Ѝ`/`ѝ` — в файле их нет). Свои шрифты пользователя — `.ttf`/`.otf`
в папке `Fonts` в корне памяти; регистрируются в рантайме (`dexp_reader_register_font`),
имя семейства берётся сравнением списка `getFaceList` до и после регистрации.
**Грабля:** `make_fonts.py` пересобирает весь список `JOBS` и перезаписывает уже лежащие
начертания (`literata_500/600`, `manrope_200/300/500`) — они те же по размеру, но в git
висят как изменённые; после прогона вернуть их `git checkout -- app/src/main/res/font/`.

**Грабля: русский голос TTS лежит не в `app_voices`.** `TextToSpeech` (`com.google.android.tts`,
`/system/app/TTS`) держит скачанные голоса в `/data/user_de/0/com.google.android.tts/app_voices_greco_v15/`
(`ru-ru-x-dfc`, 32 МБ; в интерфейсе движка «Набор голосов 1, 64 МБ»), а `/data/data/.../app_voices`
пуст — по нему нельзя судить, есть ли русский. Проверять так: `adb root` (на книге root доступен),
`adb shell ls /data/user_de/0/com.google.android.tts/app_voices_greco_v15`, либо через приложение —
строка «tts: lang ru=1 default(ru_RU)=1» в трассе. Запасной `com.svox.pico` (`/system/tts/lang_pico`)
знает только de/en/es/fr/it — русского у него нет; без проверки `isLanguageAvailable` движок молча
читает кириллицу латиницей. Экран движка для проверки:
`adb shell am start -n com.google.android.tts/.local.voicepack.ui.VoiceDataInstallActivity`.
**Грабля: `adb root` роняет сервер.** После него `adb` перестаёт отвечать (`Address already in use`);
лечится `pkill -f 'adb.*server'` и `adb start-server`.

**Грабля: `install -r` не перезапускает процесс.** Новая `.so` лежит в APK, но приложение
осталось с загруженной старой — новый JNI-метод не находится (`UnsatisfiedLinkError: No
implementation found ...`), хотя в APK символ есть. После `adb install -r` нужен
`adb shell am force-stop ru.ogrpfsense.dexpm8` (и только потом запуск). Проверка символа
в APK: `unzip -o -q app-debug.apk 'lib/*'` и `llvm-readelf --dyn-syms -W lib/armeabi-v7a/libdexp_cre.so | grep <метод>`.
Ещё замечание: `assembleDebug` собирает натив в свою папку, и отдельная сборка `ninja -C /tmp/r0-build`
на APK не влияет — для установки нужен именно Gradle.

**Грабля: имя гарнитуры в crengine — ровно как в шрифте.** `setDefaultFontFace` ищет семейство
по `name`-таблице файла, а не по имени файла: для `/system/fonts/DroidSansMono.ttf` это
«Droid Sans Mono» (с пробелами), для `literata_400.ttf` — «Literata». Написал без пробела —
настройка «применилась» без ошибки, но разметка не изменилась (перевёрстка 3 мс вместо 1,2 с,
число страниц то же). Проверять по имени:
`python3 -c "from fontTools.ttLib import TTFont; f=TTFont('/tmp/mono.ttf'); print(f['name'].getDebugName(1))"`

**Служба «DEXP M8 — выключение» (клавиша «Выключение» на шторке B5, п. 8.1).** Экрана «Спец. возможности»
в прошивке книги нет (`cmd package resolve-activity -a android.settings.ACCESSIBILITY_SETTINGS` → `No activity found`),
поэтому службу включают один раз через adb. Переживает перезагрузку; слетает при сбросе книги и при удалении оболочки
(переустановка `install -r` её не снимает). Без службы клавиша пишет «Нужна настройка».

```bash
adb shell settings put --user 0 secure enabled_accessibility_services ru.ogrpfsense.dexpm8/ru.ogrpfsense.dexpm8.core.PowerService
adb shell settings put --user 0 secure accessibility_enabled 1
adb shell dumpsys accessibility | grep -o 'services:{[^}]*}'   # ожидаем «DEXP M8 — выключение»
# выключить: adb shell settings delete --user 0 secure enabled_accessibility_services; adb shell settings put --user 0 secure accessibility_enabled 0
```

**Грабля (2026-11-20, второй раз): без `--user 0` запись может пройти молча.** `settings put secure …`
вернул код 0, а `settings list secure` остался с пустым `enabled_accessibility_services` и
`accessibility_enabled=0`. С `--user 0` (или `cmd settings put --user 0 …`) записалось с первого раза,
служба поднялась — в трассе `power: service connected`. Если после переустановки ключ «Выключение»
пишет «Нужна настройка» — сначала проверить `settings list secure | grep accessib`, потом писать с `--user 0`.

Команда затирает список включённых служб: если на книге есть другие (по умолчанию нет, `settings get` → `null`) —
дописать через `:`.

**Грабля (2026-11-20): `am force-stop` снимает службу.** `adb shell am force-stop ru.ogrpfsense.dexpm8`
обнуляет `enabled_accessibility_services` (и `accessibility_enabled` → 0), сама служба после старта не
возвращается. А `force-stop` обязателен после каждого `install -r`, иначе приложение держит старую `.so`
(см. «Грабля: `install -r` не перезапускает процесс»). Порядок на каждую переустановку:

```bash
adb install -r -d ~/projects/dexp-m8/app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop ru.ogrpfsense.dexpm8
adb shell settings put --user 0 secure enabled_accessibility_services ru.ogrpfsense.dexpm8/ru.ogrpfsense.dexpm8.core.PowerService
adb shell settings put --user 0 secure accessibility_enabled 1
adb shell am start -n ru.ogrpfsense.dexpm8/.ui.EinkActivity
adb logcat -d | grep 'power: service connected'   # ожидаем строку трассы
```

⚠️ **Грабля (2026-11-20, переустановка): этот порядок не всегда поднимает службу сразу.**
Настройки записаны верно (`settings get secure enabled_accessibility_services` отдаёт имя службы,
`accessibility_enabled` = 1), но `dumpsys accessibility` показывает `services:{}`, а в логе видно
`ActivityManager: Scheduling restart of crashed service …/.core.PowerService`. Строки
`power: service connected` в трассе нет. Проверено 2026-11-20: повторные `put` с
`accessibility_enabled 0/1`, `delete` + `put`, `am force-stop` + `start` не помогли.
**Помогает перезагрузка книги:** после `adb reboot` служба в списке
(`services:{Service[label=DEXP M8 — выключение, …]}`) без новых команд. Грабля перезагрузки:
`adb devices` после неё отдаёт пустой список — `adb kill-server; adb start-server`, подождать
~30 с до `getprop sys.boot_completed` = 1.

**Админ устройства (нужен для «Сна» на B5, `lockNow`).** Включается из adb одной командой
(проверено 2026-11-20). Снять так же нельзя — `dpm remove-active-admin` без root отказывает
(`Attempt to remove non-test admin`), снимают руками через системный экран админов (см. «Частые ошибки»).

```bash
adb shell dpm set-active-admin ru.ogrpfsense.dexpm8/ru.ogrpfsense.dexpm8.core.AdminReceiver
adb shell dumpsys device_policy | grep -A6 'Enabled Device Admins'   # ожидаем .core.AdminReceiver и policies: force-lock
```

Без админа «Сон» на B5 показывает системный запрос права; с админом — заставка B19 и
`mWakefulness=Asleep`, `Display Power: state=OFF` (проверено 2026-11-20).

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

## Режимы и модели

Кто что берёт и стоп-шаблоны ответа — `.clinerules/02-modes.md`.

## Частые ошибки

| Симптом | Причина |
|---|---|
| `adb devices` → `offline`, потом книга пропадает; `logcat` сам завершается через 1–2 мин | обрыв USB. `adb kill-server` не помог, помогло выдернуть кабель, подождать 5 с и вставить в порт на задней панели ПК; на книге — «Передача файлов (MTP)» и «Разрешить отладку» |
| `adb` не распознан в PowerShell | запускать как `.\adb` из `C:\platform-tools` |
| `java: command not found` (`apksigner`, `d8`, `kotlinc` в `typecheck.sh`, фон) | в PATH нет JDK: `export JAVA_HOME=$HOME/.local/jdk PATH=$HOME/.local/jdk/bin:$PATH` |
| `adb: command not found` в Linux | `export PATH=$HOME/.local/android-sdk/platform-tools:$PATH` |
| Книга не видна в `adb devices` после переподключения или перезагрузки (LXC `tools`, VMID 113, USB проброшен: udev `51-android.rules`, vendor `2207`) | `pkill adb; adb start-server`, подождать 3 с. Нет `2207` в `/sys/bus/usb/devices` (`lsusb \| grep 2207`, `2207:0011`) — другой порт ПК. **Повторилось 2026-11-14** (R2.4а): кабель на месте, `lsusb` недоступен, `adb devices` пуст — `pkill adb; adb start-server` вернул `6IVJ8C68AP device` |
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
| `git push` → `HTTP Basic: Access denied`, хотя `git ls-remote` тем же токеном проходит | в терминале VS Code переменная `GIT_ASKPASS` из VS Code перекрывает проектный `core.askpass` — уходит не тот пароль. **2026-11-15:** `GIT_ASKPASS=$PWD/tools/git-askpass.sh GIT_TERMINAL_PROMPT=0 git push origin main` (рецепт из «Токены git», стр. 269) |
| В терминале Cline нет вывода долгих команд (`typecheck.sh`, Gradle) | `cmd > /tmp/x.log 2>&1`, затем читать файл |
| `adb: command not found` в терминале Cline | в PATH его нет: `~/.local/android-sdk/platform-tools/adb` (2026-11-14) |
| `screencap` отдаёт чёрный кадр | экран погас по таймауту: `adb shell input keyevent KEYCODE_WAKEUP`; сразу после запуска E-Ink ещё не обновилась — снимать через 10–15 с |
| `gradle assembleDebug` пишет `UP-TO-DATE`, а APK старый — на книге проверяется прошлая сборка | после правки Kotlin инкремент иногда не видит изменений (2026-11-17: APK 8,5 МБ вместо 5,4 МБ). Проверить `ls -l app/build/outputs/apk/debug/app-debug.apk` и время правки, при сомнении — `gradle clean assembleDebug` (~40 с) |
| `PatternSyntaxException` в статическом инициализаторе → `NoClassDefFoundError`, класс падает при каждом обращении | регекс с `{{ }}` или `}}` (`\{\{[^}]*}}`) — ICU на Android не принимает такие скобки. Не использовать регекс: разбирать строку вручную (2026-11-17, `DictOnline.stripTemplates`) |
| Викисловарь отдаёт 403 или `{"status":501}` | 403 — нет своего `User-Agent` в запросе; 501 — выбран `/api/rest_v1/page/definition` (на ru-разделе сломан, работает `action=api&prop=extracts&explaintext=1`) |
| `adb install` пишет `Success`, а на книге старый APK (нет новой строки в трассе, `--es read` не срабатывает) | сверить: `adb shell pm path <пакет>`, затем `adb shell 'cat <путь>/base.apk' \| md5sum` против `md5sum app/build/.../app-debug.apk`; не совпало — ставить через `adb push … /data/local/tmp/d.apk` + `adb shell pm install -r -d /data/local/tmp/d.apk` |
| повторный `am start … --es …` пишет «Warning: Activity not started» и экстра не доходит | Activity уже жива: перед запуском `adb shell am force-stop <пакет>` (иначе меряешь прошлый запуск) |
| Иконка не рисуется (пустая круглая клавиша), ошибок в логе нет | `icons.draw(c, "sort@36.8x30.6", …)` — такого варианта в `IconSpecs` нет, а `Icons.draw` на неизвестном имени молча выходит. Сверить имя: `grep -n '"sort' ui/IconSpecs.kt`; варианты с `@ШxВ` — это строки `design/icons.txt` (борды страницы «icons» в Penpot); нужного размера нет — нарисовать борд и снять (`design/README.md`, «Иконки: страница icons»), потом `python3 tools/icon_specs.py` (2026-11-23) |
| В файле после записи через `sed`/`cat` из терминала VS Code появились `ESC]633…` | сборка падает на первой строке; писать файлы редактором, проверять `grep -c $'\x1b' файл` = 0 |
| Скрипт с `while`/`grep -q` и `adb logcat -d` подвисает в терминале Cline | запускать по одному файлу в фоне, читать лог отдельно |
| Проба листает 30 страниц и «тормозит» | на книге остался `/data/local/tmp/dexp-bench`: `adb shell rm /data/local/tmp/dexp-bench` |
| Самотест движка (`--es reader`) падает с `Fatal signal 7 (SIGBUS), code 1 (BUS_ADRALN)` в `dexp-engine`, первые строки `page 0`/`page 1` успели пройти (2026-11-15) | битый файл кэша разметки в `cache/cre/<книга>.<хеш>.0.cr3` — движок `mmap`-ит его и читает по выровненному адресу. Удалить **только** кэш этой книги: `adb shell run-as <пакет> rm -f cache/cre/<книга>.cr3` (имя — из `ls cache/cre`), затем перезапустить. **2026-11-17:** удалять **после** `adb shell am force-stop <пакет>` — живая Activity успевает перезаписать кэш, и удаление не помогает. **Не** `pm clear` — см. ниже |
| `adb shell pm clear <пакет>` стёр библиотеку, настройки и место чтения на книге (2026-11-15) | `pm clear` снимает **все** данные приложения, включая базу и разрешение на хранилище. После него: `pm grant <пакет> android.permission.READ_EXTERNAL_STORAGE` (+`WRITE_…`), затем открыть «Библиотеку» — она пересканирует сама (531 книга). Данные не восстановить; для кэша движка или настроек есть точечные средства (`run-as … rm cache/cre/…`, «Сбросить настройки оболочки» на B43) |
| Правка C++ не действует на книге, хотя `BUILD SUCCESSFUL` (2026-11-17) | Gradle пересобрал `.so` (`buildCMakeDebug`, файл в `intermediates/cxx/…/obj` свежий), но `mergeDebugNativeLibs` остался `UP-TO-DATE` — в APK уехал старый файл. Лечение: `rm -rf app/build/intermediates/merged_native_libs app/build/intermediates/stripped_native_libs`, затем `assembleDebug`. Проверка: `find app/build -name 'libdexp_cre.so' -printf '%TH:%TM %p\n'` — даты `merged`/`stripped` должны быть свежее `cxx` |

| `tools/typecheck.sh`: `unresolved reference: BuildConfig` | `BuildConfig` генерирует только Gradle; в `typecheck.sh` и `build.sh` есть заглушка (`DEBUG = true`) с 2026-11-12 |
| `tools/typecheck.sh` / `tools/build.sh`: `OutOfMemoryError` в psi2ir или на `ShelfScreen.kt` | **решено 2026-11-13:** оба скрипта сами ставят `JAVA_OPTS=-Xmx1600m` (если переменная не задана); нужен только `JAVA_HOME` и PATH JDK. Раньше требовалось передавать вручную |
| `tools/typecheck.sh` без JDK в PATH: пишет «классов собрано: 0» и **выходит с кодом 0** | `kotlinc` не нашёл `java` и умер молча — без ошибки в логе. Всегда `export JAVA_HOME=$HOME/.local/jdk PATH=$HOME/.local/jdk/bin:$PATH`; признак успеха — «классов собрано: 135» и выше |
| JVM-харнесс ядра OPDS: `cannot access 'Companion': it is private in 'Opds'` | `companion object` в `Opds` — `private` (с 10.6); для теста собирать копию без слова `private` и вызывать `searchUrl` через экземпляр (`docs/CHECKS.md`, «Проверка разбора OPDS на хосте») |
| `db v7: VACUUM FAIL: cannot VACUUM from within a transaction` | `VACUUM` нельзя внутри `onUpgrade` (там уже открыта транзакция). Уборка после перешифрования паролей — в `LibraryDb.scrub()`, вызывается из `LibraryDb.open()` после открытия базы, не из `onUpgrade` (10.12) |
| JNI-строки из движка роняют процесс: `input is not valid Modified UTF-8: illegal continuation byte 0` (SIGABRT в `dexp-engine`) | `NewStringUTF` ждёт **Modified** UTF-8, а текст книги — обычный UTF-8; обрезка по лимиту байт ещё и рвёт символ посреди кода. Обрезать по границе символа (`10xxxxxx` в конце — отступить) и отдавать строку через UTF-16 (`utf8ToJString` в `engines/reader_jni.cpp`, R1.1) |
| Gradle, запущенный фоном (`nohup … &`), падает с `Bad file descriptor` / зависает с пустым логом | терминал Cline закрывает stdin фонового процесса: `setsid ./gradlew assembleDebug --offline </dev/null > /tmp/build.log 2>&1 &`, затем читать `/tmp/build.log` |
| `assembleDebug` пишет `UP-TO-DATE`, а APK старый в глазах «ничего не собралось» | APK мог быть собран раньше той же сессией: сверить `stat -c '%y %n'` у `app-debug.apk` и правленого `.kt` — APK должен быть новее |
| `gradle` / `gradlew` «не найден» | Gradle в PATH не стоит: `~/.local/gradle-8.7/bin/gradle` (или `export PATH=$HOME/.local/gradle-8.7/bin:$PATH`); `gradlew` работает, но тянет дистрибутив в `~/.gradle` |
| Два вызова терминала **параллельно** (два `run_commands` в одном ответе): вывод перемешивается, «command completion could not be observed», команды попадают в чужую строку | терминал один на чат. Несколько команд — одним вызовом массивом (внутри вызов они идут последовательно) |
| «Быстрый» (B5/B37): шторка или новый экран рисуются кашей поверх старого | A2-режим на большой кадр с серым; такие кадры должны идти через `EinkActivity.bigFrame` / `fullOnSwitch` (п. 8.2) |
| `NoClassDefFoundError: kotlin.jvm.internal.Lambda` | APK без Kotlin stdlib (старый `build.sh`); исправлено 2026-11-11 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | другой ключ подписи. С 2026-11-18 (п. 20) `build.sh` и Gradle подписывают одним ключом `~/.dexp-m8-keys/release.jks`, debug ставится поверх release и наоборот. Если ключа нет — `build.sh` откатывается на отладочный, и подписи расходятся. Иначе `adb uninstall` (данные пропадут; база и `shell.xml` снимаются `run-as` заранее — см. «Смена подписи без потери данных») |
| `adb uninstall` → `Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]` (2026-11-18) | «Лист» был активным администратором устройства (`core/AdminReceiver`, политика `force-lock` для «Сна» на B5); активного админа система не даёт снести вместе с пакетом. Снять: `am start -n com.android.settings/.DeviceAdminSettings` → касание по «Лист» → «Отключить приложение администратора устройства» → «Отключить». `dpm remove-active-admin` без root не работает (`Attempt to remove non-test admin`), `ro.debuggable=1` этого не разрешает |
| `adb shell run-as <pkg> sh -c 'cat > databases/library.db'` → `can't create …: No such file or directory` (2026-11-18) | редирект разбирает внешний shell: путь относительный и попадает не в каталог приложения, а `run-as` получает уже открытый поток. Писать с абсолютным путём: `adb shell "run-as $P sh -c 'cat > /data/data/$P/databases/library.db'" < library.db`. Перед заливкой удалить `-wal`/`-shm`/`-journal`, после — сверить `sha256sum` через `run-as` |
| `run-as` не работает | APK не debuggable; значения проверять через `settings get`/`getprop` |
| `screencap` отдаёт белый/чёрный кадр на первом шаге мастера | экран успел погаснуть или E-Ink не обновилась: `input keyevent KEYCODE_WAKEUP`, перезапуск экрана, снимать через 10–15 с. Диалог права на память при холодном старте перекрывает кадр — это норма, жать «РАЗРЕШИТЬ» по координатам (≈1106, 998) |
| Пустая строка компиляции: `unresolved reference: content/net` при обращении к `android.content.*` | в экране есть поле с именем `android` (строка «Android 8.1» на B63) — оно перекрывает пакет `android`. Импортировать классы явно (`import android.net.ConnectivityManager`) и не писать полные имена через `android.` |
| `git ls-remote` на `github.com/koreader/djvulibre` висит | брать с `gitlab.com/koreader/djvulibre` |
| `penpot_dump.js` не запустить: в чате Cline нет инструментов MCP Penpot, хотя сервер включён | инструменты MCP подключаются при старте чата: включить сервер в Cline → MCP Servers, затем открыть **новый** чат |
| Ответ `penpot_dump.js` в чате обрезан (`...[truncated N chars]...`) | целиком он лежит в `~/.cline/data/sessions/<id>/<id>.messages.json`: результат положить в `storage`, отдавать частями с меткой (`{MARK, files}`), достать из журнала по метке и сверить длину и djb2 (UTF-16) с Penpot до записи (DEVLOG 2026-11-12, 9.0) |
| Нужно снять пачку бордов: чат режет опись, по одному файлу — сотня вызовов | приёмник `/tmp/penpot_recv.py` на `0.0.0.0:8765` + `fetch` из `execute_code` пачками по 60 КБ; адрес — **LAN** (`hostname -I`), `127.0.0.1` из плагина не отвечает (2026-11-15, 93 файла за 8 запросов; рецепт — `design/README.md`) |
| Проба пишет «fb2': движка нет», в пути видны `'…'` | кавычки двух уровней: путь передавать как `--es probe "'$f'"` (один уровень одинарных внутри двойных) |
| `compileDebugNative` / `compileDebugCpp` «not found in root project» | такой задачи нет (придумано при поиске, 2026-11-16). Натив собирается только `assembleDebug`; отдельной цели для проб нет |
| Gradle пишет `assembleDebug UP-TO-DATE`, а `.so` в APK старее исходника | сверить времена: `stat -c '%y'` у `app/.cxx/*/…/*.o`, `app/build/intermediates/cxx/Debug/*/obj/armeabi-v7a/libdexp_cre.so` и `app/build/outputs/apk/debug/app-debug.apk`; `mergeDebugNativeLibs` мог не переложить библиотеку — помогает повторный `assembleDebug` |
| `tools/typecheck.sh` → `java: command not found`, «классов собрано: 0» | в PATH нет JDK: `export JAVA_HOME=$HOME/.local/jdk PATH=$HOME/.local/jdk/bin:$PATH` (скрипт ставит только `JAVA_OPTS`, не JDK) |
| `adb shell sqlite3 /data/data/…/library.db` → `unable to open database file` / данные «не записались» (0 строк), хотя лог приложения пишет «поставлена» | у базы включён WAL (`enableWriteAheadLogging`): свежие строки лежат в `library.db-wal`, без него файл показывает старое состояние. Читать так: `adb exec-out run-as <pkg> cat databases/library.db > /tmp/x.db` **и** `… cat databases/library.db-wal > /tmp/x.db-wal` в тот же каталог, затем открывать `/tmp/x.db`. Приватный файл `sqlite3` не откроет — только `run-as` + `exec-out` |
| `pragma user_version` не вырос после `adb install -r` | `onUpgrade` ленив: `SQLiteOpenHelper` обновляет схему при первом обращении к базе, то есть после запуска приложения (2026-11-16) |
| `adb` / `git log` / `git show` в терминале Cline отдают мусор: `<D1><81><D0>…` и `\b` внутри слов, вывод обрывается | вывод идёт через постраничный просмотр (`less`), русский текст в pager кодируется октетами. Всегда `git --no-pager log …` (то же для `git show`, `git diff`) |
| `getprop sys.gray.gammalevel` пуст (`[]`), хотя контраст выставляли | `sys.*` — свойства только в памяти: **сбрасываются при каждой загрузке книги**, прошивка своего значения не задаёт. После `adb shell setprop … 36` значение живёт до перезагрузки, а в приложении — только пока не прочитано заново. Поэтому последнее выставленное деление дублируется в `Prefs.gammaLevel` (2026-11-16) |
| `adb root` → «restarting adbd as root», затем `no devices/emulators found` насовсем | на книге `adb root` отваливает USB-отладку до **перезагрузки устройства** (`adb kill-server`/`reconnect` не помогают, хотя на шине `2207:0011 M8` видна). Root для отладки оболочки не нужен — хватает `run-as`; если всё же запустили, только `adb reboot` |
| `tools/build.sh --install`: самотест пишет «`selftest: open rc=-100 .so не загрузилась`», затем «`ВСЁ ОК`», а `--es read` открывает библиотеку вместо книги (2026-11-17) | `build.sh` кладёт в APK только Kotlin — **нативных `.so` в нём нет** (см. таблицу окружения: «без `.so`», ~0,9 МБ). Для проверки читалки, словаря и DjVu — только `gradle assembleDebug` (8,5 МБ): в APK должны быть `libdexp_core.so`, `libdexp_cre.so`, `libdexp_djvu.so` |
| `--es read <id>` не открывает книгу: видна главная B1, в трассе нет `b3: open` | `read` — это **открыть читалку B3** по `_id` из базы; `--es reader <путь>` — совсем другое, **самотест движка** (`EngineSelfTest`, кегли 8…60, смена типографики), он роняет процесс на замечании 19. Книгу в читалке открывать только `--es read` |
