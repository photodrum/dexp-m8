# П. 20 — release-подпись (2026-11-18)

## Решение

Свой ключ **на оба типа сборки**: `signingConfigs.dexp` подключается и к `release`, и к `debug`.
Альтернатива (release своим ключом, debug отладочным) отвергнута: при первой же установке release
поверх debug-сборки Android выдал бы `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, а `adb uninstall`
забирает базу с местом чтения, закладками и настройками, и ключ `AndroidKeyStore` (пароли
каталогов OPDS) вместе с пакетом. Общий ключ делает переход одноразовым и безопасным.

## Ключ

- `~/.dexp-m8-keys/release.jks` (PKCS12, 600), alias `dexp-m8`, RSA 2048, 10 000 дней.
- `~/.dexp-m8-keys/release.properties` (600): `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.
- Пароль — 48 случайных hex, сгенерирован `secrets.token_hex(24)`; в репозиторий не попадает
  (`.gitignore`: `*.jks`, `*.keystore`; каталог `~/.dexp-m8-keys` создан с правами 700).
- `CN=DEXP M8, O=ogrpfsense, C=RU`; SHA-256 сертификата `9edff2d1588a20a9fe137f0569741d21a36c9f87304d109fa2a04816a77317fc`.
- Почему не `~/.config/dexp-m8`: этот каталог принадлежит `root` (создан при настройке токена),
  писать туда под `denisle` нельзя. Каталог ключей — рядом, в отдельной папке.
- `keytool`/`apksigner` — из `$HOME/.local/jdk` и `build-tools/34.0.0`.

## Код

- `app/build.gradle.kts`: `import java.io.File/Properties`, чтение `release.properties` при
  конфигурации; `signingConfigs { create("dexp") … }` только если файл есть; `buildTypes.release`
  и `buildTypes.debug` получают один и тот же `signingConfig`. Нет файла — release собирается
  неподписанным, сборка не падает.
- `versionCode = 2`, `versionName = "0.2.0"` (0.1.0 ходила без подписи, номер не переиспользуем).
- `tools/build.sh`: те же `VERSION_CODE`/`VERSION_NAME`, ключ берётся из `release.properties`;
  при отсутствии — откат на отладочный с предупреждением в выводе.

## Перенос данных на книге (сделано в этом шаге)

Порядок (в `BUILD.md` — «Смена подписи без потери данных»):

1. `am force-stop` → `exec-out run-as` базы (`library.db`, `-wal`, `-shm`) и `shared_prefs/shell.xml`
   в `~/dexpm8-backup-2026-11-18/`. Копия на живой базе приходит пустой (`tables: []`) — это
   и стало причиной остановить приложение.
2. Сверка: `pragma integrity_check` → `ok`, 532 книги, 8 непустых `pos`, 4 закладки, 0 каталогов.
3. `adb uninstall` → `Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]`: «Лист» был активным
   администратором устройства. `dpm remove-active-admin` без root не работает; снял вручную через
   `com.android.settings/.DeviceAdminSettings` → «Отключить приложение администратора устройства».
4. Установка debug-APK с новым ключом, один запуск (создаёт каталоги данных), остановка.
5. Заливка базы и настроек: `adb shell "run-as $P sh -c 'cat > /data/data/$P/…'" < файл`
   (относительный путь в редиректе не работает — редирект разбирает внешний shell).
   Сверка `sha256sum` через `run-as` — совпала с копией.
6. `pm grant` READ/WRITE_EXTERNAL_STORAGE (после `uninstall` разрешения снимаются).

## Проверки

| Что | Результат |
|---|---|
| `assembleDebug` | BUILT, `app-debug.apk` 5 364 028 б, `versionCode 2` / `0.2.0`, подпись `CN=DEXP M8` |
| `assembleRelease` | BUILT, `app-release.apk` 4 396 812 б, `apksigner verify` → v2 `true`, сертификат тот же, три `.so` внутри |
| B1 на книге | 532 книги, «Сейчас читаю: Ловец душ, 52%» |
| Место чтения | «Ловец душ» открылся на 115/218 (52%) — как в базе (`pos` есть) |
| release поверх debug | `install -r` без удаления, `flags` без `DEBUGGABLE`, книга открывается (`b3: open ok … pos=есть`) |
| debug поверх release | `install -r` успешно, `sha256` настроек совпал с копией |
| `tools/typecheck.sh` | 231 класс, 0 ошибок |
| `python3 tools/check_design.py` | 331 файл, 249 иконок, 0 нарушений |

Снимки: `/tmp/after1.png` (B1 после восстановления), `/tmp/release1.png`, `/tmp/release2.png`
(release читает книгу).

## Открыто

- Копии ключа вне этой машины нет — **сделать до первой публикации** (потеря ключа = снос данных
  при следующем обновлении).
- `update.json` обновлён на `versionCode 2` / `0.2.0`, но `sha256` — заглушка и APK не выложен:
  публикация (`gl_publish_apk.py`) — отдельный шаг, после проверки B43/B76.
- Админ устройства после переустановки не включён заново: «Сон» по кнопке (B5) попросит включить
  администратора при первом использовании.
