# Контекст проекта — точка входа

> Вставь ссылку на этот файл в новый чат, чтобы продолжить. Обновляется при каждом коммите.
> История — `docs/DEVLOG.md`, план — `docs/ROADMAP.md`.

## СЛЕДУЮЩИЙ ШАГ

- **R1 закрыт 2026-11-13** (п. 14): FB2 и EPUB листаются вперёд и назад, место чтения переживает выход и
  `force-stop`, PSS 31,5–42,8 МБ, страница 40–92 мс. Итоги — верх `docs/DEVLOG.md`, подробности — `docs/archive/*-2026-11-13-r1.md`.
- **Дальше — R2, DjVu** (`docs/ROADMAP.md`, п. 15; `docs/READER_SPEC.md`, раздел R2): `DjvuEngine` за интерфейсом `Engine`,
  экраны B31, B45, B59. Сначала сверить B31 с чатом дизайна (без PDF), затем движок DjVuLibre (JNI).
  Тестовые файлы на книге: `/sdcard/Books/r0/r0-test.djvu`, `r0-multi.djvu` (14,5 МБ).
  **Модель:** движок и JNI — **Opus**; экраны B31/B45/B59 — Sonnet.
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

**Последнее обновление:** 2026-11-13 — приёмка R1 пройдена на книге, R1 закрыт; следующий — R2 (DjVu).
