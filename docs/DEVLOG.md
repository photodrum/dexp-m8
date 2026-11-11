# Журнал разработки

> Старые записи — `docs/archive/`: до 2026-11-10 — `DEVLOG-2026-11-07_09.md`, `DEVLOG-2026-11-10-p3.md`, `DEVLOG-2026-11-10-p3_9-p4_2.md`; п. 4 (4.3–4.7) и проверки 2026-11-10 — 11 — `DEVLOG-2026-11-10_11.md`; п. 5 (5.1–5.2), замечания 2026-11-11, полный п. 6.0 — `DEVLOG-2026-11-11-p5_6_0.md`; хвосты п. 5, автор из имени файла, п. 6.0–6.1 — `DEVLOG-2026-11-11-p5tail_6_1.md`.

## 2026-11-11 — п. 7, шаг 7.1: три `.so` движков под `armeabi-v7a` (Opus)

Собрано по «Плану 7.1» (ROADMAP), код оболочки не тронут. Флаги подбирались прямой сборкой CMake + NDK (`/tmp/r0-cmake.sh`, вне репозитория: toolchain NDK 26.3, `armeabi-v7a`, `android-27`, `c++_static`, Ninja из SDK), потом — Gradle.

**Шаг 1.** `app/build.gradle.kts`: `abiFilters` — только `armeabi-v7a` (release тоже, блок `arm64-v8a` удалён), `ANDROID_STL=c++_static`, `-std=c99` убран из общих `cFlags`. `CMakeLists.txt`: `project(... C CXX)`, C99 — свойством цели `dexp_core`, а не глобально.

**Шаги 2–5.** Файлы в `app/src/main/cpp/engines/`; `probe.h` — общие пробные входы `dexp_{mupdf,djvu,cre}_probe(path, w, h, gray)`: первая страница по центру буфера w×h, 8 бит серый, stride = w, фон 255; коды `DEXP_PROBE_*`. Общие флаги линковки — `DEXP_ENGINE_LDFLAGS` (`--gc-sections`, `--exclude-libs,ALL`, `max-page-size=16384`). Во всех библиотеках движков `-DNDEBUG` даже в Debug (antiword без DEBUG/NDEBUG не собирается; замеры 7.3 — как в release).

| `.so` | Что внутри | Файлы | Размер (strip, прямая сборка) |
|---|---|---|---|
| `libdexp_mupdf.so` | `source/{fitz,pdf,cbz}`, шрифты URW из `generated/` (уже в подмодуле), freetype slim, libjpeg, jbig2dec, openjpeg, lcms2mt; HTML-движок, JS, XPS, SVG, brotli, barcode, docx/odt/OCR выключены | `mupdf.cmake`, `mupdf_probe.c` | 3,4 МБ |
| `libdexp_djvu.so` | `libdjvu/*.cpp` по `Makefile.am` + `ddjvuapi`; без libjpeg (`NEED_JPEG_DECODER` не задан), без iconv (в bionic с API 28) | `djvu.cmake`, `djvu/config.h`, `djvu_probe.cpp` | 0,55 МБ |
| `libdexp_cre.so` | crengine по `CRE_SRCS` KOReader + `qimagescale`; freetype полный и harfbuzz 13 (исходники MuPDF, своя сборка без `fz_hb_*`), libpng 1.6.6, fribidi, libunibreak, utf8proc, xxhash, antiword; CHM, MathML, SVG, WebP, zstd, md4c выключены | `cre.cmake`, `cre/crsetup.h`, `cre/fribidi/*`, `cre_probe.cpp` | 3,1 МБ |

У всех трёх: в `NEEDED` только системные `libz` (не у djvu), `libm`, `liblog`, `libdl`, `libc`; наружу экспортирован один символ — `dexp_*_probe` (`llvm-readelf --dyn-syms`). Предупреждений компилятора в наших файлах нет (в сторонних подавлены `-w`).

**Шаг 6, Gradle `assembleDebug`:** собирается, `app-debug.apk` **8,2 МБ** (было 1,2 МБ). В APK `.so` лежат без сжатия (`Stored`, так AGP делает при `minSdk ≥ 23`): `libdexp_mupdf.so` 3 427 372, `libdexp_cre.so` 3 062 048, `libdexp_djvu.so` 547 480, `libdexp_core.so` 13 144 байт — те же размеры, что у прямой сборки после strip. Экспорт `libdexp_core.so` не изменился (4 функции `NativeBook`). Первая нативная сборка ~600 файлов — 3–4 мин, повторная с изменённым `crsetup.h` — ~1 мин. Release не собирался (подписи нет, п. 19). На книге `.so` ещё не грузились — это 7.2.

**Решения и грабли:**

- **MuPDF:** `make generate` не нужен — шрифты URW уже лежат в `generated/resources/fonts/urw/*.c`. Все `source/{fitz,pdf,cbz}/*.c` можно брать целиком: код HTML, brotli, gumbo, harfbuzz закрыт `#if FZ_ENABLE_*`. Кэш `fz_context` в пробе — 32 МБ (по умолчанию 256 МБ — много для 1 ГБ).
- **djvulibre:** `config.h` написан по `configure.ac` (AC_CHECK_* и AH_BOTTOM); собралось с первого раза. `HAVE_PTHREAD` обязателен (`GThreads.h` без потоков — `#error`).
- **fribidi:** meson нет — `tools/gen_fribidi.sh` собирает хостовым `gcc` генераторы `gen.tab/` и пишет `*.tab.i`, `fribidi-unicode-version.h`, `fribidi-config.h` в `engines/cre/fribidi/` (коммитятся, ~0,6 МБ текста). Перезапускать после обновления подмодуля.
- **crengine, `android/jni/Android.mk` устарел** (нет `textlang`, `mdfmt`, `docxfmt`, `lvopc`…) — список взят из корневого `CMakeLists.txt` KOReader.
- **crengine + JPEG: открытый вопрос.** `lvimg.cpp` использует `JCS_EXT_BGRX` — это расширение **libjpeg-turbo**; в `crengine/thirdparty/libjpeg` лежит IJG 6b, у MuPDF — IJG 9. libjpeg-turbo нет ни в одном подмодуле. Пока `USE_LIBJPEG 0` — JPEG-картинки в FB2/EPUB не покажутся (обложки!). Варианты — ROADMAP, «Открытые вопросы (п. 7)».
- **libpng 1.6.6 на ARM:** ждёт `png_init_filter_functions_neon` из `arm/*.c` — выключено `PNG_ARM_NEON_OPT=0`.
- **Шрифты в пробе crengine** — все `.ttf/.otf` из `/system/fonts`, гарнитура по умолчанию «Noto Serif». Есть ли она на прошивке книги — проверить в 7.2 (если нет, crengine возьмёт первую зарегистрированную).

## 2026-11-11 — п. 7, шаг 7.0: подмодули движков и проверка NDK (Opus)

Подмодули в `third_party/` (`.gitmodules`, у всех `shallow = true`), код оболочки не тронут:

| Подмодуль | Источник | Версия | Лицензия |
|---|---|---|---|
| crengine | github.com/koreader/crengine | `fb4d562` (master, 2026-11-08) | GPL-2.0 (см. ниже) |
| djvulibre | gitlab.com/koreader/djvulibre | `4f2359a` (master, 2026-11-04) | GPL-2.0-or-later (`COPYRIGHT`: «or any later version») |
| mupdf | github.com/ArtifexSoftware/mupdf | тег `1.28.6` (`da5f5eb`) | AGPL-3.0 |
| xxhash | github.com/Cyan4973/xxHash | `v0.8.3` | BSD-2 |
| fribidi | github.com/fribidi/fribidi | `v1.0.16` | LGPL-2.1+ |
| libunibreak | github.com/adah1972/libunibreak | `libunibreak_6_1` | zlib |
| utf8proc | github.com/JuliaStrings/utf8proc | `v2.10.0` | MIT |

У MuPDF подтянуты только вложенные `freetype` 2.14.3, `harfbuzz` 13.0.1, `libjpeg`, `zlib`, `jbig2dec`, `openjpeg`, `lcms2`, `gumbo-parser`, `brotli`. Не подтянуты `curl`, `tesseract`, `leptonica`, `freeglut`, `mujs`, `extract`, `zxing-cpp`, `zint`, `cmark-gfm`: для PDF/CBZ без JS, OCR и GUI они не нужны. Объём: рабочие файлы ~255 МБ (mupdf 153, crengine 66), `.git/modules` 147 МБ.

**NDK.** Пробная библиотека на C++17 (`vector`, `string`, исключения, `make_unique`) в `/tmp/r0-probe`, не в репозитории. Собрана CMake 3.22.1 + Ninja из SDK, NDK 26.3 (clang 17.0.2), `armeabi-v7a`, `android-27`, `c++_static`. Результат: ELF32 ARM, 419 КБ, в зависимостях только `liblog`, `libm`, `libdl`, `libc`. C++ рантайм собирается.

**Что выяснилось для 7.1:**

- `crengine/CMakeLists.txt` ищет все зависимости через `pkg-config` (`featopt … PKG`), а `libxxhash` обязателен. `pkg-config` на хосте нет, под Android он к тому же бесполезен. Варианты: свой CMake со списком исходников `crengine/src` или заранее объявить цели `PkgConfig::*` и выключить поиск. Выбор — в 7.1.
- У crengine в `thirdparty/` устаревшие freetype 2.5.0 и libpng 1.6.6. Для harfbuzz нужна версия ≥ 8.0. Решение: freetype, harfbuzz, libjpeg и zlib брать из MuPDF, общие для обоих движков, чтобы не дублировать в APK. libpng — из `crengine/thirdparty`, MuPDF его не использует. Риск: у форков Artifex своя конфигурация freetype (`FT_CONFIG_*`), проверить при сборке.
- djvulibre собирается через autotools, fribidi — через meson. На хосте нет `autoconf`, `automake`, `libtool`, `meson`, `pkg-config`; есть `make`, `python3`, `gcc`. Значит, для libdjvu и fribidi — свои короткие `CMakeLists.txt` и заранее подготовленный `config.h`.
- MuPDF генерирует `generated/` (встроенные шрифты) через `make generate`, это хостовый шаг. Сделать один раз, решить: коммитить результат или генерировать при сборке.

**Лицензия crengine — открытый вопрос (READER_SPEC, раздел 6).** В `README.TXT` написано «GNU GPL license, version 2», файла `LICENSE` в корне нет. Из 115 файлов `crengine/src/*.cpp` и `include/*.h` формулировка «or any later version» есть только в трёх (`mdfmt.cpp`, `crgui.h`, `crskin.h`). В остальных стоит «GNU General Public License, See LICENSE file», без номера версии. Если это GPL-2.0-only, его нельзя совмещать с AGPL-3.0 (MuPDF) в одном APK. KOReader поставляет ту же связку, но это не снимает вопрос. Для пробы R0 (без распространения) не блокер, но решить нужно до первого релиза читалки: спросить у мейнтейнеров KOReader, как трактуют, или держать MuPDF отдельно. Решает пользователь.


## 2026-11-11 — п. 6, шаг 6.2: проверка на книге (Sonnet)

Сборка на книге была с 6.1 (APK 20:36 UTC, установка по часам книги 23:37). Ручной счёт миганий ненадёжен, поэтому в `EinkActivity.fullOnSwitch` добавлен лог пропуска `epd: switch skip N/M`, в `switch full` — `clean_on_switch=`. Логика не менялась.

- **B37 «Очищать при смене экрана» выключено:** logcat за 5 циклов — `skip 1/5 … 4/5`, на 5-м `epd: switch full -> true (clean_on_switch=false)`, счётчик сбрасывается. Ровно раз в 5 переходов.
- **После системного диалога (B37 «Яркость», WRITE_SETTINGS):** `eink: clean after dialog (epd full) -> true`, следа на экране нет, белого кадра нет.
- Замечание 6 закрыто, п. 6 закрыт.

**Грабли.** `editor` путает пробелы в многострочном `old_text` — менять по одной строке. Длинный python в heredoc терминала Cline не выполняется — писать скрипт файлом. `adb logcat` в фоне — `nohup … &` с фильтром `DexpM8:I '*:S'`.

## 2026-11-11 — оптимизация документов и правил (Sonnet)

Даты 2026-11-12 в `CONTEXT/ROADMAP/DEVLOG` исправлены на 2026-11-11 (модель ошиблась с датой; в правила добавлено «даты — по `date`»). `DEVLOG.md` 38 → 10 КБ: записи от п. 5.2 и старше и полный текст 6.0 → `archive/DEVLOG-2026-11-11-p5_6_0.md`. `ROADMAP.md` 16 → 11 КБ: «Пункт 5 — подробно» → `archive/ROADMAP-2026-11-11c.md`, хвосты п. 5 — в «Открытые замечания», дубль «Ждут чата дизайна» убран. `CONTEXT.md`: убраны копии хвостов и списка для дизайна, строка про эталоны `build/ref/` → `DESIGN_USAGE.md`, добавлен `COMPARISON.md`. `BUILD.md`: грабли (`adb` в `PATH`, вывод в `/tmp/x.log`, `java` в фоне, USB) в «Частые ошибки». `.clinerules/01-project.md`: шаги 1 и 5 объединены, «язык» убран (дубль промпта), добавлены приоритет правил над проактивностью и «что куда писать». `02-modes.md`: Plan сокращён. Код не тронут.

Чистка по `date`: п. 6 закрыт, «Пункт 6 — подробно» → `archive/ROADMAP-2026-11-11d.md`, записи 6.1, 6.0, автор из имени, хвосты п. 5 → `archive/DEVLOG-2026-11-11-p5tail_6_1.md`. В `ROADMAP` добавлен «Пункт 7 — подробно». Файл `archive/ROADMAP-2026-11-12.md` назван ошибочной датой — оставлен из-за ссылок. Код не тронут.
