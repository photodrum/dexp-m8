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
| Debug APK | `~/.local/gradle-8.7/bin/gradle -p ~/projects/dexp-m8 assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk` (965 КБ) |
| Release APK | `... assembleRelease` | `app/build/outputs/apk/release/app-release-unsigned.apk` (145 КБ) |
| Быстро, без Gradle | `~/projects/dexp-m8/tools/build.sh` | `build/app-debug.apk` — **без `.so` и без Kotlin stdlib: на книге падает (`NoClassDefFoundError kotlin.jvm.internal.Lambda`); ставить только Gradle-сборку** |
| Только типы Kotlin | `~/projects/dexp-m8/tools/typecheck.sh` | ~10 сек |
| Тесты C-ядра | см. ниже | 34 проверки |

Первая сборка Gradle ~2 минуты (качает AGP и CMake), повторная ~40–70 сек.

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
```

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
| `401` на `/api/v4/mcp` | legacy-токен вместо fine-grained, либо `PRIVATE-TOKEN` вместо `Authorization: Bearer` |
| `403 insufficient_granular_scope` | у токена нет нужного ресурса — дописать в Edit PAT |
| `403 [Code: Push]` | у токена нет `write_repository` |
| `Permission denied (publickey,password)` | SSH уходит в NPM, а не в GitLab — использовать HTTPS |
| Gradle: `'-' is not a valid file-based resource name` | в `res/` только `a-z0-9_` |
| `UnsatisfiedLinkError` в release | R8 выкинул `NativeBook` — нужен `-keep class ... { *; }` |
| `aapt2: <manifest> must have a 'package' attribute` | AGP 8 вырезает `package`; в `build.sh` подставляем `sed` |
