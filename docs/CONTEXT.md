# Контекст проекта — точка входа

> Вставь ссылку на этот файл в новый чат, чтобы продолжить. Обновляется при каждом коммите.
> История — `docs/DEVLOG.md`, план — `docs/ROADMAP.md`.

## СЛЕДУЮЩИЙ ШАГ

- **R2 (DjVu, п. 15) идёт.** ✅ R2.1 (2026-11-14) — сеанс DjVu за `Engine` (код и сборка, верх `docs/DEVLOG.md`).
- **Дальше — R2.2, проверка на книге (Opus):** подключить книгу к adb, поставить APK, открыть
  `/sdcard/Books/r0/r0-test.djvu` и `r0-multi.djvu` (14,5 МБ) из библиотеки; листание вперёд/назад,
  место чтения после выхода и `force-stop`, время страницы (`djvu: page N rc=0 Xms`), PSS; битый `.djvu` → B45, а не падение.
- **Потом R2.3 (Sonnet):** B59 при `EPAGE`; B31 — после снимка в `design/v5` и ответа чата дизайна, что остаётся без PDF.
- **Порядок:** оболочка (пп. 1–10) и R1 закрыты → R2 (п. 15) → пп. 12–13 (плеер, система) отложены — `docs/ROADMAP.md`.
- **Открытые замечания:** 4 (ждёт `B37.json`), 8 (значок Wi-Fi), 7 и 10 по B42 (сделаны, на книге нет) — `docs/ROADMAP.md`.
- **Для читалки и `.so`** — Gradle `assembleDebug` по полному пути `~/.local/gradle-8.7/bin/gradle`
  (в PATH его нет) с `JAVA_HOME=$HOME/.local/jdk`, не `tools/build.sh` (`docs/BUILD.md`).
  Проверка без книги невозможна: `pm clear` сбрасывает разрешение на хранилище — вернуть
  `adb shell pm grant ru.ogrpfsense.dexpm8 android.permission.READ_EXTERNAL_STORAGE`.

## Проект в двух словах

Оболочка и читалка для DEXP M8 Prudentia (E-Ink 1404×1872, Android 8.1, `armeabi-v7a`, 1 ГБ ОЗУ):
Kotlin на `Canvas` без AndroidX, C-ядро FB2 через JNI, затем CREngine / DjVuLibre (без PDF).
Макеты — Penpot, файл «UI», страница v5; числа — из `design/v5/*.json`. Лицензия — GPL-2.0-only.

**Правила и карта документов:** `.clinerules/`, `README.md` («Документация»).

**Последнее обновление:** 2026-11-14 — R2.1: сеанс DjVu за `Engine` (код и сборка); следующий — R2.2, проверка на книге.
