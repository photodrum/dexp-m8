# Контекст проекта — точка входа

> Вставь ссылку на этот файл в новый чат, чтобы продолжить. Обновляется при каждом коммите.
> История — `docs/DEVLOG.md`, план — `docs/ROADMAP.md`.

## СЛЕДУЮЩИЙ ШАГ

**п. 3, шаг 3.6 — разрешение `READ_EXTERNAL_STORAGE` во время работы (`AndroidManifest.xml`,
`EinkActivity.kt`).** `targetSdk` 27, `Activity.requestPermissions` без AndroidX; без разрешения
`Scanner.scan()` возвращает `skipped` и ничего не удаляет (DEVLOG 2026-11-10, шаг 5).
**Модель Act: Opus — поставь claude-opus-5.5 на вкладке Act** (`EinkActivity` — архитектура).
Шаги 3.1–3.5 сделаны 2026-11-10 (3.2 — B11 на заглушке, на книге не проверено, сверка в 3.8;
3.3 — `NativeBook.metaFb2`; 3.4 — `core/LibraryDb.kt`; 3.5 — `core/Scanner.kt`; 3.4 и 3.5 на книге
не запускались). Все шаги 3.1–3.8 и модели для Act — в `docs/ROADMAP.md`.

Вопросы в чат дизайна (полный текст — в ROADMAP):
- `icon_specs.py --check` падает: размеры `back`, `list`, `grid` на B11 меньше, чем на B2
  (43.9×45.7 против 45.7×47.6); `IconSpecs` не менялся;
- B1: opsz Literata в макете (текст шире на ~1 %, −6 px по правому краю).

Сверка экранов на книге — `tools/compare_screen.py` (порядок в DEVLOG от 2026-11-09).

## Где что лежит

| Нужно | Файл |
|---|---|
| Полный roadmap, текущий пункт, что сделано, проверки на книге | [`docs/ROADMAP.md`](docs/ROADMAP.md) — **текущий: п. 3** |
| Окружение (`~/.local`), сборка, установка, git и токены, частые ошибки | [`docs/BUILD.md`](docs/BUILD.md) |
| Рендер, дизайн-px, структура кода, правила E-Ink, память | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) |
| Палитра, радиусы, объём по снимку, факты Penpot | [`docs/DESIGN.md`](docs/DESIGN.md) |
| Как пользоваться макетами, карта экранов B1–B76 | [`docs/DESIGN_USAGE.md`](docs/DESIGN_USAGE.md) |
| Читалка: движки, этапы R0–R6 | [`docs/READER_SPEC.md`](docs/READER_SPEC.md) |
| Журнал по датам | [`docs/DEVLOG.md`](docs/DEVLOG.md) |

## Проект в двух словах

Своя оболочка и читалка для DEXP M8 Prudentia (E-Ink 1404×1872, Android 8.1, `armeabi-v7a`,
1 ГБ ОЗУ): Kotlin на `Canvas` без AndroidX, C-ядро FB2 через JNI; читалка — CREngine / MuPDF /
DjVuLibre после оболочки. Макеты — Penpot, файл «UI», страница v5; числа берём из `design/v5/*.json`.
Лицензия — AGPL-3.0. PSS оболочки 18 МБ (потолок 35).

## Правила

- Язык — русский. Один маленький шаг за раз; действия человека — по шагам, с подтверждением.
- Замечания пользователя сначала записывать, делать после слова «делай».
- Каждый шаг: обновить `CONTEXT.md` и `DEVLOG.md`, коммит, показать, что уходит, `push` после
  подтверждения. Порядок старта и режимы Plan/Act — `.clinerules/`.
- E-Ink: только G0–G15, без анимаций, прозрачности, размытия и скролла; дизайн-px, не dp.
- Макет в Penpot из Cline не менять.

**Последнее обновление:** 2026-11-10 — п. 3, шаг 3.5: сканер книг `Scanner`.
