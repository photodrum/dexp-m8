# DEXP M8 Prudentia — кастомная оболочка

Полная замена родного софта книги DEXP M8 Prudentia: лаунчер, библиотека,
читалка, плеер. Дизайн — страница **v5** файла «UI» в Penpot (76 бордов) и спецификация
на странице «v5 дизайн-система». Код — Kotlin без AndroidX и Compose, разбор метаданных
книг — C99 через JNI; страницы книг (план) — CREngine, MuPDF, DjVuLibre.
Лицензия — AGPL-3.0.

## Документация

| Файл | О чём |
|---|---|
| [`docs/CONTEXT.md`](docs/CONTEXT.md) | **начинать отсюда**: следующий шаг, карта документов, правила работы |
| [`docs/ROADMAP.md`](docs/ROADMAP.md) | полный план (оболочка → читалка), подробности текущего пункта, что сделано |
| [`docs/DEVLOG.md`](docs/DEVLOG.md) | журнал по датам: что сделано, какие баги найдены |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | как устроен рендер, дизайн-px, структура кода, слои объёма, правила E-Ink, память |
| [`docs/DESIGN.md`](docs/DESIGN.md) | токены из Penpot: палитра, радиусы, кегли, геометрия B1/B2, факты Penpot |
| [`docs/DESIGN_USAGE.md`](docs/DESIGN_USAGE.md) | как пользоваться макетами «v5» и «v5 дизайн-система», карта экранов |
| [`design/README.md`](design/README.md) | снимок макета в JSON: как снимать, формат, проверка |
| [`docs/READER_SPEC.md`](docs/READER_SPEC.md) | ТЗ читалки: движки, `ReaderEngine`, этапы R0–R6, приёмка |
| [`docs/BUILD.md`](docs/BUILD.md) | окружение (`~/.local`), сборка, установка на книгу, git и доступы, частые ошибки |
| [`docs/COMPARISON.md`](docs/COMPARISON.md) | чем своё ядро отличается от Book's Story и KOReader |
| [`app/src/main/cpp/README.md`](app/src/main/cpp/README.md) | C-ядро: типы блоков, тесты, грабли |

## Железо и ограничения

| | |
|---|---|
| SoC | Rockchip PX30 (Cortex-A35, 4 ядра) |
| ОЗУ / Flash | 1 ГБ / 16 ГБ |
| ОС | Android 8.1, API 27, без Google Play |
| Экран | 7.8″ E-Ink Carta, **1404×1872 px**, 300 ppi, 16 градаций |
| Плотность | ориентировочно 2.0 → 702×936 dp |

Правила, которые нельзя нарушать:

* **никаких анимаций и переходов** — смена экрана = полная перерисовка;
* **никаких градиентов, полупрозрачности, размытия** — только 16 ступеней
  серого с шагом 17 (`G0 #000000` … `G15 #FFFFFF`);
* **никакого скролла** — строгая постраничность;
* касательные зоны **≥ 48 dp** (при density 2.0 это ≥ 96 дизайн-px);
* диалоги и листы без затемнения фона — всё под ними перерисовывается на 4–5 ступеней
  светлее (текст G1 → G8), статус-бар не трогается.

## Почему дизайн-px, а не dp

Макет нарисован в пикселях панели: 1404×1872. Числа в нём — это не dp и не sp.
Чтобы перенести их в код без пересчёта, все View рисуют в «дизайн-px»,
а `Canvas` масштабируется один раз (`D.u = screenWidth / 1404f`).

* на панели 1404 px `u = 1.0`, числа совпадают с макетом один-в-один;
* на другой ревизии экрана всё масштабируется линейно и пропорции не плывут.

Проверить фактическую плотность на устройстве:
```
adb shell getprop ro.sf.lcd_density
```

## Структура

Код — `app/src/main/java/ru/ogrpfsense/dexpm8/` (`ui/` — экраны и рисование, `core/` — база, сканер, система, JNI),
C-ядро — `app/src/main/cpp/`, утилиты — `tools/`. Подробно — `docs/ARCHITECTURE.md`, «Структура кода».

## Сборка

Окружение развёрнуто в `~/.local` (JDK 17, cmdline-tools, kotlinc 1.9.24, Gradle 8.7, NDK r26d).

### Основной способ — Gradle

```bash
export JAVA_HOME=$HOME/.local/jdk
export ANDROID_HOME=$HOME/.local/android-sdk
export PATH=$JAVA_HOME/bin:$PATH

gradle -p ~/projects/dexp-m8 assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
gradle -p ~/projects/dexp-m8 assembleRelease   # с R8
```

`local.properties` уже указывает на SDK. Gradle сам собирает `.so` через CMake
для `arm64-v8a` и `x86_64`.

Установка на книгу:

```bash
$ANDROID_HOME/platform-tools/adb install -r -d \
  app/build/outputs/apk/debug/app-debug.apk
```

### Быстрая сборка без Gradle

`tools/build.sh` делает то же самое руками (aapt2 → javac → kotlinc → d8 →
zipalign → apksigner) за ~10 секунд, но **не собирает `.so`** и не подхватывает
`.so`/CMake — только Kotlin + ресурсы. Нужен, чтобы быстро проверить логику
экранов, не дожидаясь Gradle.

```bash
./tools/build.sh              # build/app-debug.apk (без нативного ядра)
./tools/build.sh --install
./tools/typecheck.sh          # только проверка типов Kotlin, ~10 сек
```

Проверка C-ядра на хосте (без NDK и устройства):

```bash
cd app/src/main/cpp
gcc -std=gnu99 -O1 -g -Wall -Wextra -Icore -fsanitize=address,undefined \
    -o /tmp/test_core core/book.c test/test_core.c
cd test && /tmp/test_core testdata/sample.fb2
```

Состояние проекта, план и грабли — `docs/CONTEXT.md`, `docs/ROADMAP.md`, `docs/BUILD.md`.

## Лицензия

GNU Affero General Public License v3.0 (AGPL-3.0). Шрифты Manrope и Literata — SIL OFL 1.1
(`tools/fonts/OFL-*.txt`).
