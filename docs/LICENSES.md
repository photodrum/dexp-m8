# Лицензии — список для экрана B75

> Проект — **GPL-2.0-only** (решение 2026-11-11), текст — `LICENSE` в корне.
> Источник данных для экрана B75 «Лицензии»; обновлять при смене подмодулей. Версии — `git submodule status`.

## Что входит в APK

| Компонент | Версия | Лицензия | Где в проекте |
|---|---|---|---|
| crengine (koreader) | `fb4d562` | GPL-2.0 (`README.TXT`; «or later» только в 3 файлах из 115 → считаем only) | `third_party/crengine`, `libdexp_cre.so` |
| antiword (в crengine/thirdparty) | — | GPL (текст — GPL-2.0, `Docs/COPYING`) | чтение DOC, `libdexp_cre.so` |
| libpng (в crengine/thirdparty) | 1.6.6 | libpng license | `libdexp_cre.so` |
| DjVuLibre (koreader) | `4f2359a` | GPL-2.0-or-later | `third_party/djvulibre`, `libdexp_djvu.so` |
| FreeType | 2.14.3 | FTL или GPL-2.0 — берём **GPL-2.0** (FTL с GPL-2.0 несовместима) | `third_party/freetype` |
| HarfBuzz | 13.0.1 | «Old MIT» | `third_party/harfbuzz` |
| FriBidi | 1.0.16 | LGPL-2.1-or-later | `third_party/fribidi` |
| libjpeg-turbo | 3.2.1 | IJG + BSD-3-Clause | `third_party/libjpeg-turbo` |
| libunibreak | 6.1 | zlib-подобная (`LICENCE`) | `third_party/libunibreak` |
| utf8proc | 2.10.0 | MIT | `third_party/utf8proc` |
| xxHash | 0.8.3 | BSD-2-Clause (библиотека) | `third_party/xxhash` |
| zlib | из NDK | zlib | системная, не копируется |
| Manrope, Literata | — | SIL OFL 1.1 (`tools/fonts/OFL-*.txt`) | `app/src/main/res/font/` |

AndroidX, Material и Kotlin stdlib в APK не входят (`app/build.gradle.kts`, `dependencies` пусто).

## Совместимость с GPL-2.0-only

- Совместимы: MIT, BSD, zlib, libpng, IJG, LGPL-2.1+, GPL-2.0, GPL-2.0-or-later.
- Несовместимы и **не входят**: MuPDF (AGPL-3.0) — удалён в 7.3б; FreeType по FTL — используем ветку GPL-2.0.
- Шрифты OFL — отдельные файлы данных, не код.
- Юридической экспертизы нет, оценка техническая (DEVLOG 2026-11-11).

## Для B75

Показывать колонки «Название · версия · лицензия»; внизу — «Проект распространяется по GPL-2.0, исходники открыты».
Полные тексты лицензий лежат в самих подмодулях (`COPYING`, `LICENSE*`).
